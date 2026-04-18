package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

internal object AnalysisConfig {
  // Example: ws://192.168.1.50:8000/ws/analyze-frame
  const val webSocketUrl = ""

  // Send one frame every 500ms by default.
  const val frameIntervalMs = 500L

  // Useful for FastAPI routers that switch between models.
  const val modelHint = "yolov8n"

  // Lets the backend distinguish glasses streams from future phone or mock inputs.
  const val sourceName = "meta_glasses"

  // Spoken feedback is throttled to avoid repeating the same object labels every frame.
  const val speechCooldownMs = 3_000L

  val isEnabled: Boolean
    get() = webSocketUrl.isNotBlank()
}
