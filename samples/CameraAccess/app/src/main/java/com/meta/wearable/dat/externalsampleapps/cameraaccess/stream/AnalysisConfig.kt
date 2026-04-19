package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

internal object AnalysisConfig {
  // Example: http://192.168.1.50:8000/analyze-frame
  const val endpointUrl = "http://10.100.177.47:8000/analyze"

  const val ocrUrl = "http://10.100.177.47:8000/clean_ocr"


  // Send one frame every 500ms by default.
  const val frameIntervalMs = 150L

  // Useful for FastAPI routers that switch between models.
  const val modelHint = "yolov8n"

  // Lets the backend distinguish glasses streams from future phone or mock inputs.
  const val sourceName = "meta_glasses"

  val isEnabled: Boolean
    get() = endpointUrl.isNotBlank()
}
