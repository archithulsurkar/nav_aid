from fastapi import FastAPI, File, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
import cv2
import numpy as np
import base64
import time

app = FastAPI()

# 1. Load the MobileNet-SSD Model via OpenCV
print("Loading OpenCV Caffe Model...")
net = cv2.dnn.readNetFromCaffe(
    "MobileNetSSD_deploy.prototxt.txt",
    "MobileNetSSD_deploy.caffemodel"
)
print("Model loaded successfully!")

# 2. MobileNet PASCAL VOC Whitelist
WHITELISTED_HAZARDS = {
    2: "Bicycle",
    6: "Bus",
    7: "Car",
    9: "Chair",
    11: "Table",
    14: "Motorcycle",
    15: "Person"
}


def process_frame_caffe(frame):
    """Runs OpenCV DNN inference and returns both speech cues and structured detections."""
    (h, w) = frame.shape[:2]
    total_area = h * w

    corridor_left = w * 0.35
    corridor_right = w * 0.65

    blob = cv2.dnn.blobFromImage(cv2.resize(frame, (300, 300)), 0.007843, (300, 300), 127.5)
    net.setInput(blob)
    detections = net.forward()

    hazard_strings = []
    structured_detections = []

    for i in np.arange(0, detections.shape[2]):
        confidence = float(detections[0, 0, i, 2])

        if confidence > 0.50:
            class_id = int(detections[0, 0, i, 1])

            if class_id in WHITELISTED_HAZARDS:
                friendly_name = WHITELISTED_HAZARDS[class_id]

                box = detections[0, 0, i, 3:7] * np.array([w, h, w, h])
                (startX, startY, endX, endY) = box.astype("int")

                box_width = endX - startX
                box_height = endY - startY
                box_area = box_width * box_height
                object_center_x = startX + (box_width / 2)

                if (box_area / total_area) < 0.08:
                    continue

                if endY < (h * 0.40):
                    continue

                if object_center_x < corridor_left:
                    position = "on the left"
                elif object_center_x > corridor_right:
                    position = "on the right"
                else:
                    position = "dead ahead"

                hazard_strings.append(f"{friendly_name} {position}")
                structured_detections.append({
                    "label": f"{friendly_name} {position}",
                    "confidence": round(confidence, 3),
                    "bbox": [float(startX), float(startY), float(endX), float(endY)],
                })

    unique_hazards = list(set(hazard_strings))
    return unique_hazards, structured_detections


# ──────────────────────────────────────────────
# WebSocket endpoint – matches the Android app
# ──────────────────────────────────────────────
@app.websocket("/analyze")
async def analyze_frame_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    print("Android client connected via WebSocket")
    try:
        while True:
            payload = await websocket.receive_json()
            start = time.perf_counter()

            # Decode the base64 JPEG frame from the Android app
            frame_base64 = payload["frame_base64"]
            frame_bytes = base64.b64decode(frame_base64)
            nparr = np.frombuffer(frame_bytes, np.uint8)
            frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

            if frame is None:
                await websocket.send_json({
                    "model": "MobileNetSSD",
                    "latency_ms": 0,
                    "detections": [],
                    "message": "Invalid frame",
                    "speech_text": None,
                })
                continue

            hazards, detections = process_frame_caffe(frame)
            latency_ms = round((time.perf_counter() - start) * 1000.0, 2)

            if len(hazards) > 0:
                speech_text = ". ".join(hazards) + "."
            else:
                speech_text = "Path clear."

            await websocket.send_json({
                "model": "MobileNetSSD",
                "latency_ms": latency_ms,
                "detections": detections,
                "message": f"{len(detections)} hazards detected",
                "speech_text": speech_text,
            })

    except WebSocketDisconnect:
        print("Android client disconnected")
    except Exception as e:
        print(f"WebSocket error: {e}")


# ──────────────────────────────────────────────
# Original HTTP endpoint (kept as fallback)
# ──────────────────────────────────────────────
@app.post("/analyze")
async def analyze_frame(file: UploadFile = File(...)):
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if frame is None:
            return JSONResponse(status_code=400, content={"error": "Invalid image payload"})

        hazards, _ = process_frame_caffe(frame)

        if len(hazards) > 0:
            speech_string = ". ".join(hazards) + "."
        else:
            speech_string = "Path clear."

        return {"status": "success", "audio_cue": speech_string}

    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.get("/")
def read_root():
    return {"status": "OpenCV MobileNet Server is running and waiting for glasses."}