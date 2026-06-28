package com.aiopenpocket.voice.input

// @trace voice-input-1a0dfd

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [SpeechRecognizer] for the chat input. ViewModel-scoped — owner must call [release]
 * (e.g. in `ViewModel.onCleared`). All recognizer interactions are dispatched to the main
 * thread because the platform requires it.
 */
@Inject
class VoiceInputController(
  private val context: Context,
) {
  private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
  val state: StateFlow<VoiceInputState> = _state.asStateFlow()

  private val _transcripts = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
  val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

  private val mainHandler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null

  fun start(languageBcp47: String) {
    runOnMain {
      if (_state.value is VoiceInputState.Listening) return@runOnMain
      if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        _state.value = VoiceInputState.Error(VoiceInputState.ErrorReason.Unavailable)
        return@runOnMain
      }
      val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
        it.setRecognitionListener(listener)
        recognizer = it
      }
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageBcp47)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageBcp47)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      }
      _state.value = VoiceInputState.Listening(partial = null)
      r.startListening(intent)
    }
  }

  fun stop() {
    runOnMain {
      recognizer?.stopListening()
    }
  }

  /** Отмена записи: глушим распознавание без доставки результата (onResults не придёт) и сразу Idle. */
  fun cancel() {
    runOnMain {
      recognizer?.cancel()
      if (_state.value is VoiceInputState.Listening) {
        _state.value = VoiceInputState.Idle
      }
    }
  }

  fun release() {
    runOnMain {
      recognizer?.destroy()
      recognizer = null
      _state.value = VoiceInputState.Idle
    }
  }

  fun clearError() {
    if (_state.value is VoiceInputState.Error) {
      _state.value = VoiceInputState.Idle
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      _state.value = VoiceInputState.Listening(partial = null)
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle) {
      val text = partialResults
        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
      if (_state.value is VoiceInputState.Listening) {
        _state.value = VoiceInputState.Listening(partial = text)
      }
    }

    override fun onResults(results: Bundle) {
      val text = results
        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()
      _state.value = VoiceInputState.Idle
      if (text.isNotBlank()) _transcripts.tryEmit(text)
    }

    override fun onError(error: Int) {
      val reason = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputState.ErrorReason.NoMatch
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceInputState.ErrorReason.Network
        SpeechRecognizer.ERROR_AUDIO -> VoiceInputState.ErrorReason.Audio
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputState.ErrorReason.Permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceInputState.ErrorReason.Busy
        else -> VoiceInputState.ErrorReason.Other
      }
      _state.value = VoiceInputState.Error(reason)
    }
  }
}
