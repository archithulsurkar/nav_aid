from __future__ import annotations

import base64
from collections import deque
import os
import sys
import time
import cv2
import numpy as np
from fastapi import FastAPI, File, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from pydantic import BaseModel
import re

# Load ultralytics from local yolov13 fork if present
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_YOLOV13_DIR = os.path.join(_SCRIPT_DIR, "yolov13")
if os.path.isdir(_YOLOV13_DIR):
    sys.path.insert(0, _YOLOV13_DIR)

from ultralytics import YOLOWorld  # noqa: E402


MODEL_SIZE = os.getenv("WORLD_MODEL", "yolov8s-worldv2.pt")
CONFIDENCE = float(os.getenv("CONFIDENCE_THRESHOLD", "0.35"))
LEFT_CORRIDOR_RATIO = float(os.getenv("LEFT_CORRIDOR_RATIO", "0.35"))
RIGHT_CORRIDOR_RATIO = float(os.getenv("RIGHT_CORRIDOR_RATIO", "0.65"))
MIN_BOX_AREA_RATIO = float(os.getenv("MIN_BOX_AREA_RATIO", "0.08"))
MIN_BOTTOM_Y_RATIO = float(os.getenv("MIN_BOTTOM_Y_RATIO", "0.40"))
FRAME_HISTORY_SIZE = int(os.getenv("FRAME_HISTORY_SIZE", "3"))
MIN_STABLE_FRAMES = int(os.getenv("MIN_STABLE_FRAMES", "2"))
PATH_CLEAR_FRAMES = int(os.getenv("PATH_CLEAR_FRAMES", "3"))

# New settings for the Imminent Collision ("Ghost") cache
IMMINENT_AREA_RATIO = float(os.getenv("IMMINENT_AREA_RATIO", "0.40"))
GHOST_TIMEOUT_FRAMES = int(os.getenv("GHOST_TIMEOUT_FRAMES", "30"))

print(f"Loading YOLO-World ({MODEL_SIZE})...")
model = YOLOWorld(MODEL_SIZE)

CRITICAL = [
    "person",
    "car",
    "truck",
    "bus",
    "motorcycle",
    "bicycle",
    "scooter",
    "stairs",
    "step",
    "curb",
    "ramp",
    "escalator",
    "glass door",
    "door",
    "gate",
    "barrier",
    "bollard",
    "wall",
    "pillar",
    "pole",
    "post",
    "column",
]

MODERATE = [
    "chair",
    "table",
    "couch",
    "sofa",
    "bed",
    "desk",
    "bench",
    "suitcase",
    "backpack",
    "shopping cart",
    "trash can",
    "box",
    "wheelchair",
    "stroller",
    "trolley",
    "tree",
    "fire hydrant",
    "parking meter",
    "cone",
    "sign",
]

LOW_PRIORITY = [
    "elevator",
    "window",
    "counter",
    "reception desk",
    "traffic light",
    "stop sign",
    "crosswalk",
    "fence",
    "railing",
    "handrail",
]

ALL_CLASSES = CRITICAL + MODERATE + LOW_PRIORITY

model.set_classes(ALL_CLASSES)
print(f"Detecting {len(ALL_CLASSES)} classes - no training needed.")
print(f"Critical: {len(CRITICAL)} | Moderate: {len(MODERATE)} | Low: {len(LOW_PRIORITY)}")


class DetectionMemory:
    def __init__(self) -> None:
        self._recent_keys: deque[set[str]] = deque(maxlen=FRAME_HISTORY_SIZE)
        self._empty_frame_streak = 0
        self._announced_keys: set[str] = set()
        self.path_clear_announced = False
        
        # Ghost memory variables
        self._ghost_label: str | None = None
        self._ghost_timer = 0

    def update_recent_history(self, detections: list[dict]) -> None:
        keys = {hazard_key(det["label"], det["position"]) for det in detections}
        self._recent_keys.append(keys)

        if keys:
            self._empty_frame_streak = 0
        else:
            self._empty_frame_streak += 1

    def is_stable(self, det: dict) -> bool:
        key = hazard_key(det["label"], det["position"])
        return sum(1 for frame_keys in self._recent_keys if key in frame_keys) >= MIN_STABLE_FRAMES

    def _update_ghost(self, stable_detections: list[dict]) -> None:
        # Check if any stable detection is massive and very close
        imminent_det = None
        for det in stable_detections:
            if det["proximity_bucket"] == "near" and det["area_ratio"] > IMMINENT_AREA_RATIO:
                if imminent_det is None or det["area_ratio"] > imminent_det["area_ratio"]:
                    imminent_det = det

        if imminent_det:
            # Refresh the ghost timer with the latest large object
            self._ghost_label = imminent_det["label"]
            self._ghost_timer = GHOST_TIMEOUT_FRAMES
        elif self._ghost_timer > 0:
            # Count down if we lost it
            self._ghost_timer -= 1
            if self._ghost_timer == 0:
                self._ghost_label = None

    def next_announcement(self, detections: list[dict]) -> str | None:
        self._update_ghost(detections)

        if not detections:
            # If the screen is empty but the ghost timer is active, trigger the imminent warning
            if self._ghost_timer > 0 and self._ghost_label:
                ghost_key = f"{self._ghost_label}|immediately ahead"
                if ghost_key not in self._announced_keys:
                    self._announced_keys.add(ghost_key)
                    return f"{self._ghost_label} immediately ahead."
            return None

        tier_order = {"critical": 0, "moderate": 1, "low": 2}
        sorted_dets = sorted(detections, key=lambda d: (tier_order.get(d["tier"], 2), -d["proximity"]))

        current_keys = {hazard_key(det["label"], det["position"]) for det in sorted_dets}
        self._announced_keys.intersection_update(current_keys)
        new_keys = current_keys - self._announced_keys

        if not new_keys:
            return None

        for det in sorted_dets:
            if hazard_key(det["label"], det["position"]) in new_keys:
                # Mark the full current stable set as already announced so we
                # stay silent until the scene meaningfully changes.
                self._announced_keys = set(current_keys)
                return f"{det['label']} {det['position']}."

        return None

    def check_path_clear(self, had_stable_hazards: bool) -> bool:
        # Prevent "Path clear" if there is a ghost in memory
        if had_stable_hazards or self._ghost_timer > 0:
            self.path_clear_announced = False
            return False

        if self._empty_frame_streak >= PATH_CLEAR_FRAMES and not self.path_clear_announced:
            self._announced_keys.clear()
            self.path_clear_announced = True
            return True

        return False


_http_memory = DetectionMemory()


def position_label(cx: float, frame_w: int) -> str:
    ratio = cx / frame_w
    if ratio < LEFT_CORRIDOR_RATIO:
        return "on the left"
    if ratio > RIGHT_CORRIDOR_RATIO:
        return "on the right"
    return "dead ahead"


def proximity_score(bbox: list[float], frame_h: int) -> float:
    x1, y1, x2, y2 = bbox
    area = (x2 - x1) * (y2 - y1)
    bottom = y2 / frame_h
    return area * (0.5 + 0.5 * bottom)


def proximity_bucket(bbox: list[float], frame_h: int) -> str:
    _, _, _, y2 = bbox
    bottom_ratio = y2 / frame_h
    if bottom_ratio >= 0.80:
        return "near"
    if bottom_ratio >= 0.60:
        return "medium"
    return "far"


def hazard_key(label: str, position: str) -> str:
    return f"{label}|{position}"


def run_inference(frame_bgr: np.ndarray) -> list[dict]:
    h, w = frame_bgr.shape[:2]
    total_area = h * w
    frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)

    results = model.predict(source=frame_rgb, conf=CONFIDENCE, verbose=False)[0]
    detections: list[dict] = []

    if results.boxes is None:
        return detections

    for box in results.boxes:
        cls_name = results.names[int(box.cls.item())]
        confidence = float(box.conf.item())
        x1, y1, x2, y2 = [float(v) for v in box.xyxy[0].tolist()]

        # Calculate area_ratio to identify massive objects right in front of the camera
        area_ratio = (x2 - x1) * (y2 - y1) / total_area

        if area_ratio < MIN_BOX_AREA_RATIO:
            continue
        if y2 < h * MIN_BOTTOM_Y_RATIO:
            continue

        bbox = [x1, y1, x2, y2]
        cx = (x1 + x2) / 2
        pos = position_label(cx, w)

        detections.append(
            {
                "label": cls_name,
                "position": pos,
                "confidence": round(confidence, 3),
                "bbox": bbox,
                "area_ratio": area_ratio, # Added so memory can read it
                "proximity": proximity_score(bbox, h),
                "proximity_bucket": proximity_bucket(bbox, h),
                "tier": "critical"
                if cls_name in CRITICAL
                else "moderate"
                if cls_name in MODERATE
                else "low",
            }
        )

    detections.sort(key=lambda d: d["proximity"], reverse=True)
    return detections


def decode_frame(frame_base64: str) -> np.ndarray | None:
    try:
        frame_bytes = base64.b64decode(frame_base64)
        nparr = np.frombuffer(frame_bytes, np.uint8)
        return cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    except Exception:
        return None


def with_optional_speech(payload: dict, speech: str | None, field_name: str = "speech_text") -> dict:
    # Always include the field so Android does not fall back to `message`.
    # Use an empty string for silence to avoid TTS reading null-like values.
    payload[field_name] = speech or ""
    return payload


app = FastAPI(title="nav_aid - YOLO-World Server")


@app.get("/")
def root():
    return {
        "status": "nav_aid YOLO-World server running",
        "model": MODEL_SIZE,
        "total_classes": len(ALL_CLASSES),
        "critical": CRITICAL,
        "moderate": MODERATE,
        "low_priority": LOW_PRIORITY,
    }


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_SIZE}


@app.get("/classes")
def list_classes():
    return {
        "total": len(ALL_CLASSES),
        "critical": CRITICAL,
        "moderate": MODERATE,
        "low_priority": LOW_PRIORITY,
    }


@app.websocket("/analyze")
async def analyze_frame_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    print("Android client connected")
    memory = DetectionMemory()

    try:
        while True:
            payload = await websocket.receive_json()
            start = time.perf_counter()

            frame = decode_frame(payload.get("frame_base64", ""))
            if frame is None:
                await websocket.send_json(
                    {
                        "model": "yolo-world",
                        "latency_ms": 0,
                        "detections": [],
                        "message": "Invalid frame",
                    }
                )
                continue

            detections = run_inference(frame)
            memory.update_recent_history(detections)
            stable_detections = [det for det in detections if memory.is_stable(det)]
            has_stable_detections = len(stable_detections) > 0

            speech: str | None = None

            if has_stable_detections:
                speech = memory.next_announcement(stable_detections)
                memory.check_path_clear(True)
            else:
                # Ask memory for a ghost announcement even when detections are empty
                speech = memory.next_announcement([])
                if speech is None:
                    if memory.check_path_clear(False):
                        speech = "Path clear."

            latency = round((time.perf_counter() - start) * 1000.0, 2)

            clean = [
                {
                    "label": f"{d['label']} {d['position']}",
                    "confidence": d["confidence"],
                    "bbox": d["bbox"],
                }
                for d in detections
            ]

            # CHANGED: Strictly enforce silence. If speech is None, message is empty.
            final_message = speech if speech is not None else ""

            response = {
                "model": "yolo-world",
                "latency_ms": latency,
                "detections": clean,
                "message": final_message,
            }
            await websocket.send_json(with_optional_speech(response, speech, "speech_text"))

    except WebSocketDisconnect:
        print("Android client disconnected")
    except Exception as e:
        print(f"WebSocket error: {e}")

class OcrRequest(BaseModel):
    raw_text: str

def extract_transit_info(raw_text: str) -> str:
    """
    Cleans noisy OCR data to find navigational intent.
    Drop in your LLM-based summarization logic here if needed.
    """
    text = raw_text.upper()

    # Look for standard bus route patterns (e.g., "42 DOWNTOWN", "ROUTE 42", "BUS 42")
    # This regex looks for 1-3 digits possibly followed by a letter, and surrounding words.
    match = re.search(r'(?:ROUTE\s*)?(\d{1,3}[A-Z]?)\s+([A-Z\s]+)', text)

    if match:
        route_num = match.group(1)
        destination = match.group(2).strip()
        
        # Strip out common noise words grabbed by the OCR
        ignore_words = ["GILLIG", "DO NOT PASS", "STOP", "YIELD", "CAUTION"]
        for word in ignore_words:
            destination = destination.replace(word, "").strip()

        if destination:
            return f"Route {route_num} to {destination}"
        return f"Route {route_num}"

    # If the text doesn't look like a transit sign, return an empty string
    # so the glasses don't speak gibberish to the user.
    return ""

@app.post("/clean_ocr")
async def clean_ocr(request: OcrRequest):
    cleaned = extract_transit_info(request.raw_text)
    return {"original": request.raw_text, "cleaned_text": cleaned}


@app.post("/analyze")
async def analyze_frame(file: UploadFile = File(...)):
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if frame is None:
            return JSONResponse(status_code=400, content={"error": "Invalid image"})

        detections = run_inference(frame)
        _http_memory.update_recent_history(detections)
        stable_detections = [det for det in detections if _http_memory.is_stable(det)]
        
        if not stable_detections:
            speech = _http_memory.next_announcement([])
            if speech is None and _http_memory.check_path_clear(False):
                speech = "Path clear."
        else:
            speech = _http_memory.next_announcement(stable_detections)
            _http_memory.check_path_clear(True)

        clean = [
            {
                "label": f"{d['label']} {d['position']}",
                "confidence": d["confidence"],
                "bbox": d["bbox"],
            }
            for d in detections
        ]

        # CHANGED: Strictly enforce silence.
        final_message = speech if speech is not None else ""

        response = {
            "status": "success",
            "message": final_message,
            "detections": clean,
        }
        return with_optional_speech(response, speech, "audio_cue")

    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})