# Python analysis server

This FastAPI server accepts sampled JPEG frames over WebSocket from the Android sample app, runs a custom YOLO model on them, and can send spoken object summaries back to the phone.

## 1. Install dependencies

```bash
pip install -r requirements.txt
```

## 2. Point it at your trained weights

PowerShell:

```powershell
$env:MODEL_PATH="C:\path\to\best.pt"
$env:MODEL_NAME="nav-aid-custom"
```

Optional:

```powershell
$env:CONFIDENCE_THRESHOLD="0.35"
```

## 3. Start the server

```bash
uvicorn server:app --host 0.0.0.0 --port 8000
```

## 4. Configure the Android app

Edit:

`samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`

Set:

```kotlin
const val webSocketUrl = "ws://YOUR_COMPUTER_LAN_IP:8000/ws/analyze-frame"
const val frameIntervalMs = 500L
const val modelHint = "nav-aid-custom"
```

Use your computer's LAN IP, for example `192.168.1.50`, not `localhost`.

## WebSocket contract

The Android app sends one JSON message per sampled frame with:

- `frame_base64`: JPEG image bytes encoded as base64
- `timestamp_us`
- `width`
- `height`
- `model_hint`
- `source`

The server returns JSON shaped like:

```json
{
  "model": "nav-aid-custom",
  "latency_ms": 41.8,
  "detections": [
    { "label": "door", "confidence": 0.93, "bbox": [110.0, 32.0, 340.0, 510.0] }
  ],
  "message": "1 detections from meta_glasses at 123456us",
  "speech_text": "Detected door"
}
```

## Notes

- If your custom trained model is not YOLO-based, keep the same `/ws/analyze-frame` response shape and replace the inference block inside `server.py`.
- If your custom model needs directional speech like "chair on the left", compute that on the server and populate `speech_text`.
- `frameIntervalMs = 500L` means the phone sends 2 sampled frames per second, even though the preview stream itself can stay higher for a smoother UI.
