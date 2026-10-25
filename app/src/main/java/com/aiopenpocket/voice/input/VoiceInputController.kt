package com.aiopenpocket.voice.input

// @trace voice-input-1a0dfd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
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

  // SpeechRecognizer.cancel() асинхронно доставляет onError(ERROR_CLIENT) для прерванной сессии.
  // Этот флаг гасит ровно один такой «ложный» onError после ручной отмены, иначе UI мигнёт ошибкой.
  private var canceling = false

  fun start(languageBcp47: String) {
    runOnMain {
      if (_state.value is VoiceInputState.Listening) return@runOnMain
      if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        _state.value = VoiceInputState.Error(VoiceInputState.ErrorReason.Unavailable)
        return@runOnMain
      }
      val r = recognizer ?: createRecognizer().also {
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
      canceling = false
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
      canceling = true
      recognizer?.cancel()
      _state.value = VoiceInputState.Idle
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

  private fun createRecognizer(): SpeechRecognizer {
    val component = externalRecognizerIfDefaultIsOurs()
    return if (component != null) {
      SpeechRecognizer.createSpeechRecognizer(context, component)
    } else {
      SpeechRecognizer.createSpeechRecognizer(context)
    }
  }

  /**
   * Наш [com.aiopenpocket.voice.AssistRecognitionService] — заглушка: она нужна только для
   * регистрации помощника и на любой запрос отвечает ERROR_RECOGNIZER_BUSY. Когда приложение
   * становится помощником по умолчанию, система может сделать распознавателем по умолчанию и её —
   * и голосовой ввод в чате молча ломался бы на «занято». Поэтому, если дефолт наш или его не
   * прочитать, берём первый установленный распознаватель ЧУЖОГО пакета явно. Если дефолт чужой —
   * null, работаем через него, как раньше; если чужих нет вовсе — тоже null (системный дефолт).
   * Видимость чужих сервисов на Android 11+ даёт `<queries>` на RecognitionService в манифесте.
   */
  @Suppress("DEPRECATION") // queryIntentServices с ResolveInfoFlags — только с API 33, minSdk ниже.
  private fun externalRecognizerIfDefaultIsOurs(): ComponentName? {
    val ours = context.packageName
    val default = runCatching {
      Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE_SETTING)
    }.getOrNull()?.let(ComponentName::unflattenFromString)
    if (default != null && default.packageName != ours) return null
    return context.packageManager
      .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
      .map { it.serviceInfo }
      .firstOrNull { it.packageName != ours }
      ?.let { ComponentName(it.packageName, it.name) }
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
      canceling = false
      val text = results
        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()
      _state.value = VoiceInputState.Idle
      if (text.isNotBlank()) _transcripts.tryEmit(text)
    }

    override fun onError(error: Int) {
      // Гасим ложный onError, прилетевший вслед за ручной cancel() — состояние уже Idle.
      if (canceling) {
        canceling = false
        _state.value = VoiceInputState.Idle
        return
      }
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

  private companion object {
    /** Settings.Secure.VOICE_RECOGNITION_SERVICE — скрытая константа, поэтому строкой. */
    const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"
  }
}
