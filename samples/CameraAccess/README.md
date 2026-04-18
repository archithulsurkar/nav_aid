# Camera Access App

A sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, and managing connection states.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Share captured photos

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Add your personal access token (classic) to the `local.properties` file (see [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup))
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Capture photos
   - View and save captured photos
   - Disconnect from the device

## Low-latency analysis streaming

This workspace also includes a WebSocket client for sending sampled frames to a FastAPI server on another machine and receiving live analysis responses back.

Configure it here:

- `app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`

Set:

- `webSocketUrl` to your FastAPI endpoint, for example `ws://192.168.1.50:8000/ws/analyze-frame`
- `frameIntervalMs` to control how often frames are sent

Client message format:

- JSON over WebSocket
- fields: `frame_base64`, `timestamp_us`, `width`, `height`, `model_hint`, `source`

Suggested FastAPI response shape:

```json
{
  "model": "yolov8n",
  "latency_ms": 42.5,
  "detections": [
    { "label": "chair", "confidence": 0.91, "bbox": [12, 44, 180, 260] }
  ],
  "message": "optional human-readable summary",
  "speech_text": "chair ahead"
}
```

The stream path was also tuned for lower display latency by reducing the app-side presentation buffer.

A starter Python server for custom model inference lives in:

- `samples/analysis_server`

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
