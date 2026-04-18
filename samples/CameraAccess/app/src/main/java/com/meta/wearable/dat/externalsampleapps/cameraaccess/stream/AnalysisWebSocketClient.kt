package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal class AnalysisWebSocketClient(
    private val webSocketUrl: String,
    private val onResult: (AnalysisResult) -> Unit,
    private val onStatusChanged: (String?) -> Unit,
) {
  companion object {
    private const val JPEG_QUALITY = 60
  }

  private val client =
      OkHttpClient.Builder()
          .connectTimeout(3, TimeUnit.SECONDS)
          .readTimeout(0, TimeUnit.MILLISECONDS)
          .writeTimeout(8, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .build()

  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var isOpen = false

  fun connect() {
    if (webSocket != null) {
      return
    }

    onStatusChanged("Connecting to analysis server...")
    val request = Request.Builder().url(webSocketUrl).build()
    webSocket = client.newWebSocket(request, SocketListener())
  }

  fun disconnect() {
    isOpen = false
    webSocket?.close(1000, "client closing")
    webSocket = null
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  fun isReady(): Boolean = isOpen

  fun sendFrame(
      bitmap: Bitmap,
      presentationTimeUs: Long,
      width: Int,
      height: Int,
      modelHint: String,
      sourceName: String,
  ): Boolean {
    val socket = webSocket ?: return false
    if (!isOpen) {
      return false
    }

    val jpegBytes =
        ByteArrayOutputStream().use { output ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
          output.toByteArray()
        }

    val payload =
        JSONObject()
            .put("timestamp_us", presentationTimeUs)
            .put("width", width)
            .put("height", height)
            .put("model_hint", modelHint)
            .put("source", sourceName)
            .put("sent_at_elapsed_ms", SystemClock.elapsedRealtime())
            .put("frame_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))

    return socket.send(payload.toString())
  }

  private inner class SocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      isOpen = true
      onStatusChanged("Analysis server connected")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      runCatching { AnalysisResult.fromJson(text) }
          .onSuccess(onResult)
          .onFailure { onStatusChanged("Bad analysis response") }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      isOpen = false
      this@AnalysisWebSocketClient.webSocket = null
      onStatusChanged("Analysis socket closing")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      isOpen = false
      this@AnalysisWebSocketClient.webSocket = null
      onStatusChanged("Analysis socket closed")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      isOpen = false
      this@AnalysisWebSocketClient.webSocket = null
      onStatusChanged("Analysis socket failed")
    }
  }
}
