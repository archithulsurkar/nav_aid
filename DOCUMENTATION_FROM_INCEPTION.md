# Nav Aid Documentation From Inception

Last updated: 2026-04-18

## Purpose

This document explains the `nav_aid` project from its original idea through the current implementation state. It is intended to help a new contributor understand what the product is, why the current architecture exists, what is already working, and where future work should happen.

## Product Vision

The goal is to build a wearable navigation aid for visually impaired users that reduces reliance on a traditional cane by turning live environmental sensing into simple, non-visual guidance.

The experience should:

- detect obstacles and environmental changes in real time
- reduce cognitive load rather than overwhelm the user
- provide feedback that is discreet, fast, and easy to understand
- work in familiar and unfamiliar environments

## Core User Story

A user wears Meta smart glasses while carrying an Android phone. The glasses provide a first-person camera feed, the phone acts as the computation and networking bridge, and a remote analysis server interprets sampled frames and returns useful guidance.

The intended loop is:

1. Glasses capture the scene.
2. Android receives the live feed.
3. Android samples frames at fixed intervals.
4. A remote FastAPI server analyzes the frames.
5. The server returns structured detections and optional spoken guidance.
6. Android displays a summary and can speak the result aloud.

## Why This Architecture Was Chosen

The project started with the need to retrieve an image or video source from Meta glasses and evolve that into a navigation aid.

Several constraints shaped the architecture:

- The glasses are accessed through Meta's DAT SDK on Android.
- The phone is the natural bridge between the glasses and any off-device analysis.
- Server-side inference is more flexible than putting all model execution directly on the phone during early prototyping.
- A remote FastAPI service makes it easier for another contributor to experiment with models such as YOLOv8n and datasets such as SUN RGB-D.

That led to this topology:

`Meta glasses -> Android phone -> FastAPI server -> Android UI/TTS`

## Repository Layout

Main repository root:

- [README.md](C:/nav_aid/README.md)
- [PROJECT_PROGRESS.md](C:/nav_aid/PROJECT_PROGRESS.md)
- [DOCUMENTATION_FROM_INCEPTION.md](C:/nav_aid/DOCUMENTATION_FROM_INCEPTION.md)

Primary Android app:

- [samples/CameraAccess](C:/nav_aid/samples/CameraAccess)

Starter FastAPI analysis server:

- [samples/analysis_server](C:/nav_aid/samples/analysis_server)

## Inception Phase

The project began with a practical question: can an Android app retrieve a live feed from Ray-Ban Meta glasses using the Meta DAT SDK?

That led to the following early milestones:

- confirm that Meta's Android DAT sample supports live streaming and photo capture
- obtain the required `APPLICATION_ID` and `CLIENT_TOKEN`
- enable `Developer Mode` in the Meta AI app
- install the sample on a real Android device
- successfully connect the glasses and display the live stream

At this stage, the main achievement was proving that the glasses feed could be retrieved and displayed on the phone.

## Baseline Chosen for Development

Rather than building the integration from scratch, the project used Meta's official `CameraAccess` sample as the base application.

Reasons:

- it already handled DAT registration and permissions
- it already created a device session and stream
- it already rendered the incoming video feed
- it reduced setup risk and allowed the project to focus on the navigation-specific path

Important Android areas:

- [MainActivity.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/MainActivity.kt)
- [WearablesViewModel.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/wearables/WearablesViewModel.kt)
- [StreamViewModel.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt)
- [StreamScreen.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/StreamScreen.kt)

## Registration and Setup Challenges

There were a few integration hurdles before the feed worked:

- GitHub token setup was required because Meta distributes DAT Android packages through GitHub Packages.
- Meta registration had to be corrected by using the right `APPLICATION_ID` and `CLIENT_TOKEN`.
- The test app needed to be properly recognized by the Meta AI app before device connection succeeded.

Once the correct app credentials were used and the setup was refreshed, the stream started working on a real device.

## First Functional Success

The first confirmed working outcome was:

- Android app installed on a real phone
- phone connected to Meta AI app and glasses
- `Connect my glasses` succeeded
- live camera stream from the glasses displayed in the app

This validated the foundation needed for the rest of the navigation pipeline.

## Latency Reduction Work

After streaming worked, the next concern was latency. For a navigation aid, responsiveness matters more than perfect visual smoothness.

The sample was tuned to reduce display delay by shrinking the app-side buffer:

- lower presentation delay
- fewer queued frames
- preference for a more current frame over a smoother delayed feed

Relevant files:

- [StreamViewModel.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt)
- [PresentationQueue.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/PresentationQueue.kt)

## Remote Analysis Requirement

The next major project requirement was to make the Android app flexible enough for another contributor to integrate remote image analysis through FastAPI.

The key need was not just to display the glasses feed, but to periodically extract frames and send them to a separate system for inference.

That requirement shaped the next design decisions:

- analysis should be configurable without rewriting the streaming path
- the phone should keep displaying the feed even while inference runs elsewhere
- analysis output should come back in a structured, model-agnostic format
- the same transport should support YOLOv8n now and other models later

## Evolution of the Analysis Path

An early version of the project used per-frame HTTP uploads. The codebase later moved to a WebSocket model because the app needs two-way communication:

- Android sends sampled frames out
- the server sends live detections and optional spoken guidance back

WebSocket was a better fit for persistent low-overhead exchange than repeated short-lived HTTP requests.

## Current Android Analysis Flow

The current Android app:

- opens a DAT stream from the glasses
- converts incoming video frames into bitmaps
- displays the live feed
- samples frames at a configurable interval
- JPEG-compresses and base64-encodes the sampled frame
- sends JSON over WebSocket to the FastAPI server
- receives structured detections back
- updates an on-screen status message
- optionally speaks returned guidance

Primary config:

- [AnalysisConfig.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisConfig.kt)

Primary analysis transport:

- [AnalysisWebSocketClient.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisWebSocketClient.kt)

Response model:

- [AnalysisResult.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/AnalysisResult.kt)

Speech helper:

- [SpeechAnnouncer.kt](C:/nav_aid/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/SpeechAnnouncer.kt)

## Current Server Role

The FastAPI side is currently a starter analysis server designed to be replaced or extended by a model owner.

Its intended role is:

- accept frames over WebSocket
- decode the incoming image
- run model inference
- return detections and optional spoken feedback

Starter server files:

- [server.py](C:/nav_aid/samples/analysis_server/server.py)
- [README.md](C:/nav_aid/samples/analysis_server/README.md)
- [requirements.txt](C:/nav_aid/samples/analysis_server/requirements.txt)

## Current Interface Contract

Android sends JSON messages over WebSocket with:

- `frame_base64`
- `timestamp_us`
- `width`
- `height`
- `model_hint`
- `source`

The server is expected to respond with JSON shaped like:

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

This contract is broad enough to support YOLOv8n and future detector variants without rewriting the Android flow.

## Current State of the Product

What is already working or represented in code:

- Meta DAT Android sample is integrated and runnable
- app registration and connection flow are in place
- live stream from Meta glasses to Android has been achieved
- low-latency stream tuning has been added
- configurable remote analysis path exists
- WebSocket transport exists for live round-trip analysis
- Android can show analysis status
- Android can speak returned `speech_text`
- starter FastAPI server is present for model integration

## Known Gaps

What is not yet complete:

- full end-to-end validation of the current WebSocket analysis flow with real glasses and the remote server
- production-quality navigation logic beyond generic object detection
- hazard-specific reasoning such as drop-offs, path changes, and route-safe guidance
- benchmarking and optimization across the full system
- a finalized feedback strategy for vibration, audio prioritization, or directional guidance

## Current Risk Areas

Important technical risks still in play:

- Bluetooth and DAT transport latency
- image conversion overhead on Android
- JPEG compression and base64 overhead
- server-side decode and inference cost
- repeated or noisy spoken feedback
- object detections that are not yet translated into navigation-safe guidance
- mismatch between generic object detection and real mobility assistance needs

## Recommended Next Steps

For Android:

- verify the current WebSocket path with the remote server on a real device
- profile time spent in capture, conversion, encode, send, receive, and speech
- reduce stale-frame buildup if inference falls behind

For FastAPI and model integration:

- connect the server to YOLOv8n or a SUN RGB-D-informed inference pipeline
- return compact, stable detections
- generate `speech_text` only for actionable events
- consider directional summaries such as `chair left`, `door ahead`, or `path blocked`

For product evolution:

- move from generic object reporting to mobility guidance
- define which classes and scene conditions actually matter to the user
- design a feedback policy that prioritizes urgent hazards over background detections

## Short Summary

The project began as an effort to retrieve and use a live video feed from Meta smart glasses on Android, then evolved into a prototype navigation-assistance pipeline that streams from the glasses, displays the feed on the phone, samples frames for remote analysis, and prepares for YOLOv8n-style inference through a FastAPI server. The current repository already contains the Android bridge, the real-time analysis contract, the speech-feedback path, and a starter Python server, making it a strong base for the next phase of navigation-focused model and UX development.
