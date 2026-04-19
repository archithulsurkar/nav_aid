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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.withContext
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

  // OCR Scanner Variables
  private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  private var isOcrActive = false
  private var ocrTimeoutJob: Job? = null
  private var lastOcrFrameElapsedMs: Long = 0L

  // HTTP Client for NLP Server
  private val httpClient = OkHttpClient()

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

            // CHANGED: Boosted to VideoQuality.HIGH for clean OCR reads
            session
              ?.addStream(StreamConfiguration(videoQuality = VideoQuality.HIGH, 24))
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

  fun triggerOcrScan() {
    if (isOcrActive) return

    Log.d(TAG, "Starting 2-second OCR Burst")
    isOcrActive = true

    ocrTimeoutJob?.cancel()
    ocrTimeoutJob = viewModelScope.launch {
      delay(2000L)
      if (isOcrActive) {
        isOcrActive = false
        Log.d(TAG, "OCR timeout - no text found.")
      }
    }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) return

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update { it.copy(isCapturing = false) }
          }
      }
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

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

      // Pass the unscaled, HIGH-RES frame directly to ML Kit
      if (isOcrActive) {
        processFrameForOcr(bitmap)
      }

      // Downscale and upload for YOLO
      maybeUploadFrameForAnalysis(bitmap, videoFrame)
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun processFrameForOcr(bitmap: Bitmap) {
    val now = SystemClock.elapsedRealtime()

    if (now - lastOcrFrameElapsedMs < 500L) {
      return
    }
    lastOcrFrameElapsedMs = now

    val ocrBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    val image = InputImage.fromBitmap(ocrBitmap, 0)

    textRecognizer.process(image)
      .addOnSuccessListener { visionText ->
        val detectedText = visionText.text.trim()

        if (detectedText.length >= 2 && isOcrActive) {
          Log.d(TAG, "Raw OCR Found: $detectedText")
          isOcrActive = false
          ocrTimeoutJob?.cancel()

          // Send raw text to Python server for NLP filtering
          viewModelScope.launch {
            val cleanedText = cleanTextViaServer(detectedText)
            if (!cleanedText.isNullOrBlank()) {
              Log.d(TAG, "Cleaned NLP Text: $cleanedText")
              speechAnnouncer.speakOcrPriority(cleanedText)
            } else {
              Log.d(TAG, "Server filtered out the text as irrelevant.")
            }
          }
        }
      }
      .addOnFailureListener { e -> Log.e(TAG, "OCR Processing failed", e) }
      .addOnCompleteListener { ocrBitmap.recycle() }
  }

  private suspend fun cleanTextViaServer(rawText: String): String? = withContext(Dispatchers.IO) {
    try {
      // UPDATE THIS URL to match where your FastAPI server is running
      val serverUrl = AnalysisConfig.ocrUrl

      val jsonBody = JSONObject().apply { put("raw_text", rawText) }.toString()
      val request = Request.Builder()
        .url(serverUrl)
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()

      httpClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          val responseBody = response.body?.string() ?: return@withContext null
          val jsonResponse = JSONObject(responseBody)
          return@withContext jsonResponse.optString("cleaned_text", "").takeIf { it.isNotBlank() }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clean text on server", e)
    }
    return@withContext null
  }

  private fun maybeUploadFrameForAnalysis(bitmap: Bitmap, videoFrame: VideoFrame) {
    if (!AnalysisConfig.isEnabled) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastAnalysisUploadElapsedMs < AnalysisConfig.frameIntervalMs) return
    val socket = analysisSocket
    if (socket == null || !socket.isReady()) return
    if (analysisEncodeJob?.isActive == true) return

    // CHANGED: The Downscaler. Target YOLO's preferred size of 640px max.
    val maxDimension = 640
    val ratio = Math.min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)

    val frameSnapshot = if (ratio < 1f) {
      val scaledWidth = Math.round(ratio * bitmap.width)
      val scaledHeight = Math.round(ratio * bitmap.height)
      Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    } else {
      bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    }

    lastAnalysisUploadElapsedMs = now
    analysisEncodeJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val accepted =
            socket.sendFrame(
              bitmap = frameSnapshot,
              presentationTimeUs = videoFrame.presentationTimeUs,
              width = frameSnapshot.width,   // Send the new, downscaled width
              height = frameSnapshot.height, // Send the new, downscaled height
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

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val byteArray = ByteArray(photo.data.remaining())
          photo.data.get(byteArray)

          val exifInfo = getExifInfo(byteArray)
          val transform = getTransform(exifInfo)
          decodeHeic(byteArray, transform)
        }
      }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix
    }

    when (
      exifInfo.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    session?.stop()
    session = null
    speechAnnouncer.shutdown()
    textRecognizer.close()
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