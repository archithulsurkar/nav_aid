from __future__ import annotations

import io
import os
import time
from typing import Any

import numpy as np
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from PIL import Image
from ultralytics import YOLO
import base64


MODEL_PATH = os.getenv("MODEL_PATH", "best.pt")
DEFAULT_MODEL_NAME = os.getenv("MODEL_NAME", "custom-yolo")
CONFIDENCE_THRESHOLD = float(os.getenv("CONFIDENCE_THRESHOLD", "0.25"))

app = FastAPI(title="nav_aid analysis server")
model = YOLO(MODEL_PATH)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "model_path": MODEL_PATH}


@app.post("/analyze-frame")
async def analyze_frame(
    frame: UploadFile = File(...),
    timestamp_us: int = Form(...),
    width: int = Form(...),
    height: int = Form(...),
    model_hint: str = Form(DEFAULT_MODEL_NAME),
    source: str = Form("meta_glasses"),
) -> dict[str, Any]:
    start = time.perf_counter()
    frame_bytes = await frame.read()
    image = Image.open(io.BytesIO(frame_bytes)).convert("RGB")
    image_np = np.array(image)

    result = model.predict(source=image_np, conf=CONFIDENCE_THRESHOLD, verbose=False)[0]
    detections = []

    boxes = result.boxes
    if boxes is not None:
        for box in boxes:
            cls_index = int(box.cls.item())
            confidence = float(box.conf.item())
            x1, y1, x2, y2 = [float(value) for value in box.xyxy[0].tolist()]
            detections.append(
                {
                    "label": result.names.get(cls_index, str(cls_index)),
                    "confidence": confidence,
                    "bbox": [x1, y1, x2, y2],
                }
            )

    latency_ms = round((time.perf_counter() - start) * 1000.0, 2)

    return {
        "model": model_hint or DEFAULT_MODEL_NAME,
        "latency_ms": latency_ms,
        "detections": detections,
        "message": f"{len(detections)} detections from {source} at {timestamp_us}us",
        "speech_text": build_speech_text(detections),
        "frame_size": {"width": width, "height": height},
    }


@app.websocket("/ws/analyze-frame")
async def analyze_frame_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    try:
        while True:
            payload = await websocket.receive_json()
            start = time.perf_counter()
            frame_base64 = payload["frame_base64"]
            timestamp_us = int(payload["timestamp_us"])
            width = int(payload["width"])
            height = int(payload["height"])
            model_hint = payload.get("model_hint", DEFAULT_MODEL_NAME)
            source = payload.get("source", "meta_glasses")

            frame_bytes = base64.b64decode(frame_base64)
            image = Image.open(io.BytesIO(frame_bytes)).convert("RGB")
            image_np = np.array(image)

            result = model.predict(source=image_np, conf=CONFIDENCE_THRESHOLD, verbose=False)[0]
            detections = extract_detections(result)
            latency_ms = round((time.perf_counter() - start) * 1000.0, 2)

            await websocket.send_json(
                {
                    "model": model_hint or DEFAULT_MODEL_NAME,
                    "latency_ms": latency_ms,
                    "detections": detections,
                    "message": f"{len(detections)} detections from {source} at {timestamp_us}us",
                    "speech_text": build_speech_text(detections),
                    "frame_size": {"width": width, "height": height},
                }
            )
    except WebSocketDisconnect:
        return


def extract_detections(result: Any) -> list[dict[str, Any]]:
    detections: list[dict[str, Any]] = []
    boxes = result.boxes
    if boxes is None:
        return detections

    for box in boxes:
        cls_index = int(box.cls.item())
        confidence = float(box.conf.item())
        x1, y1, x2, y2 = [float(value) for value in box.xyxy[0].tolist()]
        detections.append(
            {
                "label": result.names.get(cls_index, str(cls_index)),
                "confidence": confidence,
                "bbox": [x1, y1, x2, y2],
            }
        )
    return detections


def build_speech_text(detections: list[dict[str, Any]]) -> str | None:
    if not detections:
        return None

    top_detections = sorted(detections, key=lambda item: item["confidence"], reverse=True)[:3]
    labels: list[str] = []
    for detection in top_detections:
        label = detection["label"]
        if label not in labels:
            labels.append(label)

    if not labels:
        return None
    return "Detected " + ", ".join(labels)
