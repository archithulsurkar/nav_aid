package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.json.JSONArray
import org.json.JSONObject

internal data class AnalysisDetection(
    val label: String,
    val confidence: Double,
    val boundingBox: List<Double> = emptyList(),
)

internal data class AnalysisResult(
    val model: String? = null,
    val detections: List<AnalysisDetection> = emptyList(),
    val latencyMs: Double? = null,
    val message: String? = null,
) {
  companion object {
    fun fromJson(json: String): AnalysisResult {
      val root = JSONObject(json)
      val detectionsJson = root.optJSONArray("detections") ?: JSONArray()
      val detections =
          buildList {
            for (index in 0 until detectionsJson.length()) {
              val detection = detectionsJson.optJSONObject(index) ?: continue
              add(
                  AnalysisDetection(
                      label = detection.optString("label", "unknown"),
                      confidence = detection.optDouble("confidence", 0.0),
                      boundingBox = detection.optJSONArray("bbox").toDoubleList(),
                  ),
              )
            }
          }

      return AnalysisResult(
          model = root.optString("model").takeIf { it.isNotBlank() },
          detections = detections,
          latencyMs = root.optDouble("latency_ms").takeUnless { it.isNaN() },
          message = root.optString("message").takeIf { it.isNotBlank() },
      )
    }
  }
}

private fun JSONArray?.toDoubleList(): List<Double> {
  if (this == null) {
    return emptyList()
  }

  return buildList {
    for (index in 0 until length()) {
      add(optDouble(index))
    }
  }
}
