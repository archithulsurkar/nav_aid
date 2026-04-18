# Nav Aid Project Progress

Last updated: 2026-04-18

This document captures the work completed so far for the `nav_aid` prototype, starting from repository setup through the current WebSocket-based remote inference architecture.

## Session Provenance

- First known working session ID: `019d9e87-bfce-78d1-8310-da9334ee9867`

## Project Goal

Build an Android app that connects to Ray-Ban Meta glasses through the Meta DAT SDK, samples camera frames, sends them to a Python server running on a different machine, receives live object analysis back, and provides audio feedback to the user.

## Initial Repository Setup

- Repository cloned to `C:\nav_aid`
- Primary app surface identified as `samples/CameraAccess`
- Core stream path identified in:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/YuvToBitmapConverter.kt`
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/StreamScreen.kt`

## Discovery Phase

Before new implementation work began, the sample already contained an early HTTP-based analysis path:

- `AnalysisConfig.kt`
- `AnalysisFrameUploader.kt`
- `AnalysisResult.kt`
- UI support for showing `analysisStatus`

That meant the project did not need inference support built from scratch. Instead, the work shifted toward completing and reshaping the path for the actual target architecture.

## Phase 1: Remote Python Server Scaffolding

The first implementation pass assumed the Android app would send sampled frames to a Python server over HTTP.

### Changes made

- Added `android:usesCleartextTraffic="true"` in:
  - `samples/CameraAccess/app/src/main/AndroidManifest.xml`
- Added a starter FastAPI inference server in:
  - `samples/analysis_server/server.py`
  - `samples/analysis_server/requirements.txt`
  - `samples/analysis_server/README.md`
- Updated `samples/CameraAccess/README.md` to mention the analysis server scaffold

### Purpose

This established a working baseline for:

- sending sampled frames every `500ms`
- running a custom model on another machine
- returning detections to the app

## Clarification of Deployment Topology

The architecture was then clarified:

- The Python inference server will run on a different machine
- The Android phone is the bridge between the glasses and the server
- The glasses do not communicate with the server directly

The correct mental model is:

`Ray-Ban Meta glasses -> Bluetooth -> Android phone -> network -> Python server -> Android phone audio/UI`

## Phase 2: Transport Migration to WebSocket

Because the app needs not only frame upload but also live analysis data sent back to the phone, the transport was changed from one-request-per-frame HTTP to a persistent WebSocket connection.

### Android changes

- Added OkHttp dependency:
  - `samples/CameraAccess/gradle/libs.versions.toml`
  - `samples/CameraAccess/app/build.gradle.kts`
- Replaced HTTP config with WebSocket config in:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`
- Removed the old HTTP uploader:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisFrameUploader.kt`
- Added persistent WebSocket client:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisWebSocketClient.kt`
- Extended response model to support spoken feedback:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisResult.kt`
- Updated stream lifecycle and frame send path:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`

### Server changes

- Added WebSocket endpoint:
  - `samples/analysis_server/server.py`
- Kept the existing HTTP endpoint as a fallback/reference path
- Updated analysis server docs:
  - `samples/analysis_server/README.md`
- Updated CameraAccess docs:
  - `samples/CameraAccess/README.md`

### Current transport contract

Android sends JSON messages over WebSocket with:

- `frame_base64`
- `timestamp_us`
- `width`
- `height`
- `model_hint`
- `source`

Server responds with JSON containing:

- `model`
- `latency_ms`
- `detections`
- `message`
- `speech_text`

## Phase 3: Spoken Feedback

To support audio guidance back to the user, Android text-to-speech support was added.

### Changes made

- Added speech helper:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/SpeechAnnouncer.kt`
- Wired spoken responses into the analysis flow in:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`
- Added `speechCooldownMs` to avoid repeated chatter:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`
- Added `speech_text` parsing to:
  - `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisResult.kt`

### Behavior

- The server can return a short phrase such as `Detected chair`
- The app displays analysis text on screen
- The app also speaks the returned `speech_text`
- Repeated phrases are throttled to reduce audio spam

## Current End-to-End Architecture

### Frame path

1. Ray-Ban Meta glasses capture video
2. DAT SDK streams frames to the Android phone
3. `StreamViewModel` receives `VideoFrame`
4. `YuvToBitmapConverter` converts I420 to `Bitmap`
5. The app displays the live preview
6. Every `500ms`, the app samples a frame for inference
7. The sampled frame is JPEG-compressed and base64-encoded
8. The frame is sent over a persistent WebSocket connection
9. The Python server decodes the image and runs inference
10. The server sends detections and `speech_text` back
11. Android updates the overlay and speaks the result

### Current configuration points

Primary Android runtime config:

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`

Primary server runtime config:

- `samples/analysis_server/server.py`
- environment variables:
  - `MODEL_PATH`
  - `MODEL_NAME`
  - `CONFIDENCE_THRESHOLD`

## Current State of the Codebase

### Changed files

- `samples/CameraAccess/README.md`
- `samples/CameraAccess/app/build.gradle.kts`
- `samples/CameraAccess/app/src/main/AndroidManifest.xml`
- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt`
- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisResult.kt`
- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`
- `samples/CameraAccess/gradle/libs.versions.toml`
- `samples/analysis_server/README.md`
- `samples/analysis_server/requirements.txt`
- `samples/analysis_server/server.py`

### New files

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisWebSocketClient.kt`
- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/SpeechAnnouncer.kt`
- `samples/analysis_server/README.md`
- `samples/analysis_server/requirements.txt`
- `samples/analysis_server/server.py`

### Removed file

- `samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisFrameUploader.kt`

## Validation Completed So Far

- Repository successfully cloned to `C:\nav_aid`
- Python server file syntax checked with `python -m py_compile samples\analysis_server\server.py`
- Code inspection completed for:
  - stream lifecycle
  - frame conversion
  - preview pipeline
  - analysis transport
  - response handling
  - TTS flow

## Validation Not Yet Completed

- Full Android Gradle build has not yet been run after the WebSocket migration
- End-to-end device testing with actual Ray-Ban Meta glasses has not yet been run in this session
- End-to-end network test between Android phone and remote server machine has not yet been run in this session
- Real model benchmarking and latency profiling have not yet been completed

## Known Latency Hotspots

The current implementation works conceptually, but several areas were identified for future latency reduction:

- Bluetooth transport from glasses to phone
- I420 to `Bitmap` conversion on Android
- preview-frame cloning in `PresentationQueue`
- separate bitmap cloning for upload
- JPEG compression on the phone
- base64 expansion over WebSocket
- image decode and RGB conversion on the Python server
- model inference time
- TTS startup and playback delay
- stale-frame buildup if inference falls behind the sampling rate

## Recommended Next Workstreams

### 1. First real integration test

- Start the Python server on the remote machine
- Point `webSocketUrl` to that machine's LAN IP
- Run the Android app
- Confirm socket connection, detections, and spoken output

### 2. Latency profiling

- Add stage-by-stage timestamps
- Measure encode, transport, decode, inference, and speech delay separately
- Confirm whether the bottleneck is on Android, network, or server

### 3. Model-specific server adaptation

- Replace or tailor the current YOLO inference block if the production model is not YOLO-based
- Use `speech_text` to encode directional or task-specific guidance

### 4. Low-latency optimization pass

- Reduce frame size
- eliminate stale frame queue buildup
- consider binary WebSocket frames instead of base64 JSON
- reduce copies on Android
- use a smaller or accelerated inference runtime on the server

## One-Paragraph Summary

The project began by cloning and inspecting the Meta DAT sample app, identifying an existing but incomplete HTTP analysis path, then adding a starter remote Python inference server, and finally migrating the transport to a persistent WebSocket architecture so the Android phone can send sampled frames to a different machine and receive live detections plus spoken guidance back. The codebase now contains the main building blocks for remote inference with audio feedback, while full device validation, real network testing, and latency optimization remain as the next practical milestones.
