package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aiopenpocket.voice.output.speakableText
import com.aiopenpocket.voice.output.speechChunks
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The voice of a call: the phone's own TextToSpeech (Meta has no speech synthesis, see
 * VoiceReplySpeaker), fed piece by piece while the reply is still being written, and reporting
 * through [speaking] when it has gone quiet, which is when the call may listen again.
 *
 * VoiceReplySpeaker reads one finished reply and never needs to know when it is done; a call needs
 * both, hence a class of its own. ViewModel-scoped: the owner calls [release] in `onCleared`.
 * Everything touching the engine runs on the main thread.
 */
@Inject
class CallSpeaker(
  private val context: Context,
) {
  private val _speaking = MutableStateFlow(false)

  /** True while anything is queued or being read. Set at once by [say] and [stop], on the main thread. */
  val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

  private val _caption = MutableStateFlow<Caption?>(null)

  /**
   * The piece being read right now and how far into it the voice is: the call screen's running caption.
   * Kept after the voice goes quiet (the screen shows it only while [speaking]); [stop] clears it.
   */
  val caption: StateFlow<Caption?> = _caption.asStateFlow()

  /**
   * [spokenTo] is the end of the word being read, as the engine reports it (`onRangeStart`), or
   * [UNKNOWN] when the engine reports no word ranges: then the whole piece is shown as said.
   */
  data class Caption(val text: String, val spokenTo: Int = UNKNOWN) {
    companion object {
      const val UNKNOWN = -1
    }
  }

  /** The text of every utterance handed to the engine and not finished yet, by id. Main thread only. */
  private val chunkTexts = HashMap<String, String>()

  private val mainHandler = Handler(Looper.getMainLooper())
  private var tts: TextToSpeech? = null
  private var ready = false
  private var locale: Locale = Locale.US

  /** Pieces said before the engine was ready. */
  private val waiting = ArrayDeque<String>()

  /** Utterances handed to the engine and not finished yet. Main thread only. */
  private val inFlight = HashSet<String>()
  private var nextId = 0

  /** Starts the engine ahead of the first reply, so the first sentence is not delayed by its start-up. */
  fun warmUp(languageBcp47: String) {
    runOnMain {
      locale = Locale.forLanguageTag(languageBcp47)
      if (tts == null) {
        tts = TextToSpeech(context.applicationContext) { status -> onEngineReady(status) }
      } else if (ready) {
        tts?.let(::applyVoice)
      }
    }
  }

  /** Queues [markdown] after whatever is being read. */
  fun say(markdown: String) {
    val text = speakableText(markdown)
    if (text.isBlank()) return
    runOnMain {
      val engine = tts
      when {
        engine != null && ready -> enqueue(engine, text)
        engine != null -> {
          waiting += text
          _speaking.value = true
        }
        // No warmUp, or the engine failed to start: the text stays on screen, the call goes on in silence.
        else -> Unit
      }
    }
  }

  /** Stops at once and drops everything queued: the person cut the reply off or hung up. */
  fun stop() {
    runOnMain {
      waiting.clear()
      inFlight.clear()
      chunkTexts.clear()
      tts?.stop()
      _speaking.value = false
      _caption.value = null
    }
  }

  fun release() {
    runOnMain {
      waiting.clear()
      inFlight.clear()
      chunkTexts.clear()
      tts?.stop()
      tts?.shutdown()
      tts = null
      ready = false
      _speaking.value = false
    }
  }

  private fun onEngineReady(status: Int) {
    val engine = tts ?: return
    if (status != TextToSpeech.SUCCESS) {
      android.util.Log.w(TAG, "TextToSpeech init failed: $status")
      engine.shutdown()
      tts = null
      waiting.clear()
      _speaking.value = false
      return
    }
    ready = true
    engine.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    )
    engine.setOnUtteranceProgressListener(progress)
    applyVoice(engine)
    while (waiting.isNotEmpty()) enqueue(engine, waiting.removeFirst())
    if (inFlight.isEmpty()) _speaking.value = false
  }

  /**
   * The best installed voice for the language: an offline one first (a network voice makes a round trip
   * per sentence, and a call reads a reply sentence by sentence), then the right country, then quality.
   */
  private fun applyVoice(engine: TextToSpeech) {
    val result = engine.setLanguage(locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      android.util.Log.w(TAG, "No voice for $locale, using the engine default")
      return
    }
    val best = runCatching { engine.voices }.getOrNull().orEmpty()
      .filter { it.locale.language == locale.language }
      .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
      .maxWithOrNull(
        compareBy<Voice>(
          { !it.isNetworkConnectionRequired },
          { it.locale.country == locale.country },
          { it.quality },
        ),
      )
    if (best != null) engine.voice = best
  }

  private fun enqueue(engine: TextToSpeech, text: String) {
    for (chunk in speechChunks(text, TextToSpeech.getMaxSpeechInputLength())) {
      val id = "call-${nextId++}"
      inFlight += id
      chunkTexts[id] = chunk
      if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
        inFlight -= id
        chunkTexts -= id
      }
    }
    _speaking.value = inFlight.isNotEmpty()
  }

  // Callbacks arrive on the engine's binder thread. An id that [stop] already forgot is ignored, so a
  // late "done" of a cut-off sentence cannot end the next reply early.
  private val progress = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {
      runOnMain { utteranceId?.let(chunkTexts::get)?.let { _caption.value = Caption(it) } }
    }

    // Not every engine reports word ranges; Google's does. Without them the caption shows the whole piece.
    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
      runOnMain {
        utteranceId?.let(chunkTexts::get)?.let { _caption.value = Caption(it, spokenTo = end.coerceIn(0, it.length)) }
      }
    }

    override fun onDone(utteranceId: String?) = finished(utteranceId)
    override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = finished(utteranceId)
    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
  }

  private fun finished(utteranceId: String?) {
    runOnMain {
      utteranceId?.let(chunkTexts::remove)
      if (utteranceId != null && inFlight.remove(utteranceId) && inFlight.isEmpty() && waiting.isEmpty()) {
        _speaking.value = false
      }
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private companion object {
    const val TAG = "CallSpeaker"
  }
}
