/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
  application: Application,
  private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: Session? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null
  private val speechAnnouncer = SpeechAnnouncer(application.applicationContext)
  private var analysisSocket: AnalysisWebSocketClient? = null
  private var analysisEncodeJob: Job? = null
  private var lastAnalysisUploadElapsedMs: Long = 0L

  // NEW: OCR Scanner Variables
  private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private var isOcrActive = false
  private var ocrTimeoutJob: Job? = null
  private var lastOcrFrameElapsedMs: Long = 0L

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()
    analysisEncodeJob?.cancel()
    analysisEncodeJob = null
    lastAnalysisUploadElapsedMs = 0L
    presentationQueue?.stop()
    presentationQueue = null
    ensureAnalysisSocket()

    val queue =
      PresentationQueue(
        bufferDelayMs = 20L,
        maxQueueSize = 3,
        onFrameReady = { frame ->
          _uiState.update {
            it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
          }
        },
      )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      Wearables.createSession(deviceSelector)
        .onSuccess { createdSession ->
          session = createdSession
          session?.start()
        }
        .onFailure { error, _ -> Log.e(TAG, "Failed to create session: ${error.description}") }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    sessionStateJob =
      viewModelScope.launch {
        session?.state?.collect { currentState ->
          if (currentState == DeviceSessionState.STARTED) {
            videoJob?.cancel()
            stateJob?.cancel()
            errorJob?.cancel()
            stream?.stop()
            stream = null
            session
              ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob =
                  viewModelScope.launch {
                    stream?.videoStream?.collect { handleVideoFrame(it) }
                  }
                stateJob =
                  viewModelScope.launch {
                    stream?.state?.collect { currentState ->
                      val prevState = _uiState.value.streamSessionState
                      _uiState.update { it.copy(streamSessionState = currentState) }

                      val wasActive = prevState !in SESSION_TERMINAL_STATES
                      val isTerminated = currentState in SESSION_TERMINAL_STATES
                      if (wasActive && isTerminated) {
                        stopStream()
                        wearablesViewModel.navigateToDeviceSelection()
                      }
                    }
                  }
                errorJob =
                  viewModelScope.launch {
                    stream?.errorStream?.collect { error ->
                      if (error == StreamError.HINGE_CLOSED) {
                        stopStream()
                        wearablesViewModel.navigateToDeviceSelection()
                      }
                    }
                  }
                stream?.start()
              }
          }
        }
      }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    analysisEncodeJob?.cancel()
    analysisEncodeJob = null

    // Cleanup OCR
    ocrTimeoutJob?.cancel()
    isOcrActive = false

    lastAnalysisUploadElapsedMs = 0L
    analysisSocket?.disconnect()
    analysisSocket = null
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update { INITIAL_STATE }
    stream?.stop()
    stream = null
    session?.stop()
    session = null
  }

  // =====================================================================
  // NEW: OCR TRIGGER
  // =====================================================================
  fun triggerOcrScan() {
    if (isOcrActive) return

    Log.d(TAG, "Starting 2-second OCR Burst")
    isOcrActive = true

    // Optional: play a subtle sound so the user knows it's scanning
    // speechAnnouncer.speakOcrPriority("Scanning...")

    ocrTimeoutJob?.cancel()
    ocrTimeoutJob = viewModelScope.launch {
      // Wait 2 seconds
      delay(2000L)

      // If it's still active after 2 seconds, no readable text was found.
      if (isOcrActive) {
        isOcrActive = false
        Log.d(TAG, "OCR timeout - no text found.")
      }
    }
  }
  // =====================================================================

  fun capturePhoto() { /* implementation untouched for brevity */ }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) { /* implementation untouched for brevity */ }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val bitmap =
      YuvToBitmapConverter.convert(
        videoFrame.buffer,
        videoFrame.width,
        videoFrame.height,
      )
    if (bitmap != null) {
      presentationQueue?.enqueue(
        bitmap,
        videoFrame.presentationTimeUs,
      )

      // NEW: Intercept frame for OCR if active
      if (isOcrActive) {
        processFrameForOcr(bitmap)
      }

      maybeUploadFrameForAnalysis(bitmap, videoFrame)
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  // =====================================================================
  // NEW: OCR Frame Processor
  // =====================================================================
  private fun processFrameForOcr(bitmap: Bitmap) {
    val now = SystemClock.elapsedRealtime()

    // Sample at roughly 2 frames per second to save battery & processing power
    if (now - lastOcrFrameElapsedMs < 500L) {
      return
    }
    lastOcrFrameElapsedMs = now

    // Copy the bitmap so it doesn't get recycled while ML Kit is reading it
    val ocrBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    val image = InputImage.fromBitmap(ocrBitmap, 0)

    textRecognizer.process(image)
      .addOnSuccessListener { visionText ->
        val detectedText = visionText.text.trim()

        // Basic filter: only read if there's an actual word/number
        if (detectedText.length >= 2 && isOcrActive) {
          Log.d(TAG, "OCR Found: $detectedText")
          isOcrActive = false // Stop scanning immediately
          ocrTimeoutJob?.cancel()

          // Read the text and mute YOLO temporarily
          speechAnnouncer.speakOcrPriority(detectedText)
        }
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "OCR Processing failed", e)
      }
      .addOnCompleteListener {
        ocrBitmap.recycle() // Clean up memory
      }
  }
  // =====================================================================

  private fun maybeUploadFrameForAnalysis(bitmap: Bitmap, videoFrame: VideoFrame) {
    if (!AnalysisConfig.isEnabled) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastAnalysisUploadElapsedMs < AnalysisConfig.frameIntervalMs) return
    val socket = analysisSocket
    if (socket == null || !socket.isReady()) return
    if (analysisEncodeJob?.isActive == true) return

    val frameSnapshot = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    lastAnalysisUploadElapsedMs = now
    analysisEncodeJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val accepted =
            socket.sendFrame(
              bitmap = frameSnapshot,
              presentationTimeUs = videoFrame.presentationTimeUs,
              width = videoFrame.width,
              height = videoFrame.height,
              modelHint = AnalysisConfig.modelHint,
              sourceName = AnalysisConfig.sourceName,
            )
          if (!accepted) {
            _uiState.update { it.copy(analysisStatus = "Analysis socket busy") }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Frame send failed", e)
          _uiState.update { it.copy(analysisStatus = "Analysis send failed") }
        } finally {
          frameSnapshot.recycle()
        }
      }
  }

  private fun ensureAnalysisSocket() {
    if (!AnalysisConfig.isEnabled || analysisSocket != null) return
    analysisSocket =
      AnalysisWebSocketClient(
        webSocketUrl = AnalysisConfig.endpointUrl,
        onResult = ::handleAnalysisResult,
        onStatusChanged = { status ->
          status?.let { message -> _uiState.update { it.copy(analysisStatus = message) } }
        },
      )
    analysisSocket?.connect()
  }

  private fun handleAnalysisResult(result: AnalysisResult) {
    updateAnalysisStatus(result)
    speechAnnouncer.speakIfNeeded(result.speechText ?: result.message)
  }

  private fun updateAnalysisStatus(result: AnalysisResult) {
    val topDetection = result.detections.maxByOrNull { it.confidence }
    val model = result.model ?: AnalysisConfig.modelHint
    val summary =
      when {
        topDetection != null ->
          "$model: ${topDetection.label} ${(topDetection.confidence * 100).toInt()}% (${result.detections.size} detections)"
        result.message != null -> "$model: ${result.message}"
        else -> "$model: no detections"
      }

    _uiState.update { it.copy(analysisStatus = summary) }
  }

  // Photo handlers removed for brevity (keep your existing decodeHeic methods exactly as they are)

  override fun onCleared() {
    super.onCleared()
    stopStream()
    session?.stop()
    session = null
    speechAnnouncer.shutdown()
    textRecognizer.close() // Clean up ML Kit
  }

  class Factory(
    private val application: Application,
    private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
          application = application,
          wearablesViewModel = wearablesViewModel,
        ) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}