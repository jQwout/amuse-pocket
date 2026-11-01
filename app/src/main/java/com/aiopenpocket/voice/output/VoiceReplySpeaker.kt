package com.aiopenpocket.voice.output

// @trace voice-assistant-mode-a81a99

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import dev.zacsweers.metro.Inject
import java.util.Locale

/**
 * Reads an agent's reply aloud with the phone's own speech engine.
 *
 * The Meta Model API has no speech synthesis (checked 1 Nov 2026: the model list has only
 * muse-voice-transcribe, speech to text, and /v1/audio/speech is a 404), so the voice comes from
 * Android's TextToSpeech: free, offline, in whatever voice the person picked in the system.
 *
 * ViewModel-scoped like VoiceInputController: the owner calls [release] in `onCleared`. The engine is
 * created on the first [speak], not up front: most chats never read anything aloud.
 */
@Inject
class VoiceReplySpeaker(
  private val context: Context,
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var tts: TextToSpeech? = null
  private var ready = false

  /** A reply that came in while the engine was still starting; read as soon as it is ready. */
  private var pending: Pair<String, Locale>? = null

  /** Speaks [markdown] in [languageBcp47], cutting off whatever was being read before. */
  fun speak(markdown: String, languageBcp47: String) {
    val text = speakableText(markdown)
    if (text.isBlank()) return
    val locale = Locale.forLanguageTag(languageBcp47)
    runOnMain {
      val engine = tts
      when {
        engine != null && ready -> say(engine, text, locale)
        engine != null -> pending = text to locale
        else -> {
          pending = text to locale
          tts = TextToSpeech(context.applicationContext) { status -> onEngineReady(status) }
        }
      }
    }
  }

  /** Stops reading at once: the person started talking or typing again. */
  fun stop() {
    runOnMain {
      pending = null
      tts?.stop()
    }
  }

  fun release() {
    runOnMain {
      pending = null
      tts?.stop()
      tts?.shutdown()
      tts = null
      ready = false
    }
  }

  private fun onEngineReady(status: Int) {
    val engine = tts ?: return
    if (status != TextToSpeech.SUCCESS) {
      // No engine on the phone (rare, but an emulator image can lack one): stay silent rather than fail the chat.
      android.util.Log.w(TAG, "TextToSpeech init failed: $status")
      pending = null
      engine.shutdown()
      tts = null
      return
    }
    ready = true
    pending?.let { (text, locale) -> say(engine, text, locale) }
    pending = null
  }

  private fun say(engine: TextToSpeech, text: String, locale: Locale) {
    // A missing voice for the language is not a reason to say nothing: the engine's default voice reads it.
    val languageResult = engine.setLanguage(locale)
    if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
      android.util.Log.w(TAG, "No voice for $locale, using the engine default")
    }
    val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
    chunks.forEachIndexed { index, chunk ->
      val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      engine.speak(chunk, mode, null, "reply-$index")
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private companion object {
    const val TAG = "VoiceReplySpeaker"
  }
}
