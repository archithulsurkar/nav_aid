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


  override fun onInit(status: Int) {
    val tts = textToSpeech ?: return
    if (status == TextToSpeech.SUCCESS) {
      tts.language = Locale.US
      isInitialized = true
    }
  }

  fun speakIfNeeded(text: String?) {
    val normalized = text?.trim().orEmpty()
    if (!isInitialized || normalized.isBlank()) {
      return
    }

    val now = SystemClock.elapsedRealtime()
    if (now - lastSpokenElapsedMs < 2000L) {
      return
    }

    lastSpokenText = normalized
    lastSpokenElapsedMs = now
    textToSpeech?.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "analysis_speech")
  }

  fun shutdown() {
    textToSpeech?.stop()
    textToSpeech?.shutdown()
    textToSpeech = null
    isInitialized = false
  }
}
