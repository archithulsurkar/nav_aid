package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

internal class SpeechAnnouncer(context: Context) : TextToSpeech.OnInitListener {
  private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)
  private var isInitialized = false
  private var lastSpokenText: String? = null
  private var lastSpokenElapsedMs: Long = 0L

  // NEW: Suppress normal YOLO warnings while reading OCR text
  private var suppressNormalSpeechUntilMs: Long = 0L

  override fun onInit(status: Int) {
    val tts = textToSpeech ?: return
    if (status == TextToSpeech.SUCCESS) {
      tts.language = Locale.US
      isInitialized = true
    }
  }

  // Normal YOLO priority
  fun speakIfNeeded(text: String?) {
    val normalized = text?.trim().orEmpty()
    if (!isInitialized || normalized.isBlank()) {
      return
    }

    val now = SystemClock.elapsedRealtime()

    // If OCR just spoke, block YOLO for a bit so they don't talk over each other
    if (now < suppressNormalSpeechUntilMs) {
      return
    }

    if (now - lastSpokenElapsedMs < 2000L) {
      return
    }

    lastSpokenText = normalized
    lastSpokenElapsedMs = now
    textToSpeech?.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "analysis_speech")
  }

  // NEW: High Priority OCR Speech
  fun speakOcrPriority(text: String) {
    if (!isInitialized || text.isBlank()) return

    val now = SystemClock.elapsedRealtime()
    lastSpokenText = text
    lastSpokenElapsedMs = now

    // Give the user 4 seconds of peace from YOLO to digest the text read out loud
    suppressNormalSpeechUntilMs = now + 4000L

    // QUEUE_FLUSH immediately stops whatever YOLO is currently saying
    textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr_speech")
  }

  fun shutdown() {
    textToSpeech?.stop()
    textToSpeech?.shutdown()
    textToSpeech = null
    isInitialized = false
  }
}