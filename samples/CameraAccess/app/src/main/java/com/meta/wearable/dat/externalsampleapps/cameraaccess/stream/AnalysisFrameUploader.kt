package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object AnalysisFrameUploader {
  private const val JPEG_QUALITY = 70
  private const val CONNECT_TIMEOUT_MS = 3_000
  private const val READ_TIMEOUT_MS = 8_000

  fun uploadFrame(
      endpointUrl: String,
      bitmap: Bitmap,
      presentationTimeUs: Long,
      width: Int,
      height: Int,
      modelHint: String,
      sourceName: String,
  ): AnalysisResult? {
    val jpegBytes =
        ByteArrayOutputStream().use { output ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
          output.toByteArray()
        }

    val boundary = "----NavAidBoundary${UUID.randomUUID()}"
    val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

    BufferedOutputStream(connection.outputStream).use { output ->
      writeFormField(output, boundary, "timestamp_us", presentationTimeUs.toString())
      writeFormField(output, boundary, "width", width.toString())
      writeFormField(output, boundary, "height", height.toString())
      writeFormField(output, boundary, "model_hint", modelHint)
      writeFormField(output, boundary, "source", sourceName)
      writeFileField(output, boundary, "frame", "frame.jpg", "image/jpeg", jpegBytes)
      output.write("--$boundary--\r\n".toByteArray())
      output.flush()
    }

    val responseCode = connection.responseCode
    if (responseCode !in 200..299) {
      val responseBody =
          connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
      throw IllegalStateException("Upload failed: HTTP $responseCode - $responseBody")
    }

    val responseText = connection.inputStream?.bufferedReader()?.use { it.readText() }
    connection.disconnect()
    return responseText?.takeIf { it.isNotBlank() }?.let(AnalysisResult.Companion::fromJson)
  }

  private fun writeFormField(
      output: BufferedOutputStream,
      boundary: String,
      name: String,
      value: String,
  ) {
    output.write("--$boundary\r\n".toByteArray())
    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
    output.write("$value\r\n".toByteArray())
  }

  private fun writeFileField(
      output: BufferedOutputStream,
      boundary: String,
      fieldName: String,
      fileName: String,
      contentType: String,
      bytes: ByteArray,
  ) {
    output.write("--$boundary\r\n".toByteArray())
    output.write(
        "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n"
            .toByteArray(),
    )
    output.write("Content-Type: $contentType\r\n\r\n".toByteArray())
    output.write(bytes)
    output.write("\r\n".toByteArray())
  }
}
