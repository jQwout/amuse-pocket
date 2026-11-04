package com.aiopenpocket.voice.call

// @trace meta-voice-transcribe-in-calls-297760

import com.aiopenpocket.agent.core.llm.RealtimeResult
import com.aiopenpocket.agent.core.llm.RealtimeTranscribeClient
import com.aiopenpocket.agent.core.llm.TranscribeResult
import com.aiopenpocket.agent.core.llm.VoiceTranscribeClient
import com.aiopenpocket.voice.input.VoiceInputController
import com.aiopenpocket.voice.input.VoiceInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a call hears with: one phrase per [start], its text on [transcripts], and [state] in the recognizer's
 * terms the call already speaks (Listening while busy, Idle after a phrase, Error with a reason).
 *
 * Two kinds: Android's own recognizer (live words while the person speaks, any language the phone knows),
 * and Meta's Muse Voice Transcribe (a recorded phrase sent whole: no live words, but Meta's model, and the
 * call is about Meta's models; English only, Russian is not among its languages).
 */
interface CallEars {
  val state: StateFlow<VoiceInputState>
  val transcripts: SharedFlow<String>

  /** The phrase is recorded and Meta is writing it down; the call shows "Transcribing…". */
  val transcribing: StateFlow<Boolean>

  /** Whose model hears the person, for the call screen; null for the phone's own recognizer. */
  val modelName: String?

  fun start(languageBcp47: String)
  fun cancel()
  fun release()
}

/** Android's recognizer as [CallEars]: what the call always used, untouched. */
class AndroidCallEars(private val voice: VoiceInputController) : CallEars {
  override val state: StateFlow<VoiceInputState> get() = voice.state
  override val transcripts: SharedFlow<String> get() = voice.transcripts
  override val transcribing: StateFlow<Boolean> = MutableStateFlow(false)
  override val modelName: String? = null

  override fun start(languageBcp47: String) = voice.start(languageBcp47)
  override fun cancel() = voice.cancel()
  override fun release() = voice.release()
}

/**
 * Muse Voice Transcribe as [CallEars]. Live first: the microphone streams to Meta's realtime endpoint
 * ([RealtimeTranscribeClient]) and the words show while the person speaks, Meta deciding where the phrase
 * ends. If a realtime session cannot even start (the socket or the first frame is refused), the call falls
 * back for good to the recorded phrase: [PhraseRecorder] records until the person pauses, then
 * [VoiceTranscribeClient] writes the recording down.
 *
 * [state] stays Listening through a transcription too: to the call, Idle means "start listening again",
 * and a new recording must not begin while the last phrase is still being written down.
 */
class MetaCallEars(
  private val client: VoiceTranscribeClient,
  private val realtime: RealtimeTranscribeClient,
  private val recorder: PhraseRecorder = PhraseRecorder(),
) : CallEars {
  /** A realtime session could not start once; the rest of the call records whole phrases. */
  private var realtimeDown = false
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var job: Job? = null

  private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
  override val state: StateFlow<VoiceInputState> = _state.asStateFlow()

  private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 1)
  override val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

  private val _transcribing = MutableStateFlow(false)
  override val transcribing: StateFlow<Boolean> = _transcribing.asStateFlow()

  override val modelName: String = "Muse Voice Transcribe"

  override fun start(languageBcp47: String) {
    if (_state.value is VoiceInputState.Listening) return
    _state.value = VoiceInputState.Listening(partial = null)
    job = scope.launch {
      if (!realtimeDown) {
        val live = realtime.listenOnce(
          language = languageNameOf(languageBcp47),
          audio = { sink -> recorder.stream(sink) },
          onPartial = { words -> _state.value = VoiceInputState.Listening(partial = words) },
        )
        when (live) {
          is RealtimeResult.Heard -> deliver(live.text)
          RealtimeResult.NoSpeech -> fail(VoiceInputState.ErrorReason.NoMatch)
          is RealtimeResult.MicFailed ->
            fail(if (live.permission) VoiceInputState.ErrorReason.Permission else VoiceInputState.ErrorReason.Audio)
          is RealtimeResult.Failed -> {
            android.util.Log.w(TAG, "realtime session failed: ${live.why}")
            fail(VoiceInputState.ErrorReason.Network)
          }
          is RealtimeResult.NotStarted -> {
            android.util.Log.w(TAG, "realtime unavailable, recording whole phrases: ${live.why}")
            realtimeDown = true
          }
        }
        if (!realtimeDown) return@launch
      }
      val phrase = try {
        recorder.recordPhrase(onLevel = {})
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        fail(VoiceInputState.ErrorReason.Permission)
        return@launch
      } catch (e: Exception) {
        android.util.Log.w(TAG, "recording failed: ${e.javaClass.simpleName}")
        fail(VoiceInputState.ErrorReason.Audio)
        return@launch
      }
      if (phrase == null) {
        fail(VoiceInputState.ErrorReason.NoMatch)
        return@launch
      }
      _transcribing.value = true
      val result = try {
        client.transcribe(phrase.wav, phrase.durationMs, languageNameOf(languageBcp47))
      } finally {
        _transcribing.value = false
      }
      when (result) {
        is TranscribeResult.Heard -> deliver(result.text)
        is TranscribeResult.Failed -> fail(
          when (result.reason) {
            TranscribeResult.Reason.NETWORK, TranscribeResult.Reason.SERVER, TranscribeResult.Reason.RATE_LIMITED ->
              VoiceInputState.ErrorReason.Network
            TranscribeResult.Reason.NO_KEY, TranscribeResult.Reason.UNAUTHORIZED -> VoiceInputState.ErrorReason.Unavailable
            else -> VoiceInputState.ErrorReason.Other
          },
        )
      }
    }
  }

  override fun cancel() {
    job?.cancel()
    job = null
    _transcribing.value = false
    _state.value = VoiceInputState.Idle
  }

  override fun release() {
    cancel()
    scope.cancel()
  }

  private fun deliver(text: String) {
    if (text.isBlank()) {
      fail(VoiceInputState.ErrorReason.NoMatch)
      return
    }
    // Idle first, then the text: the same order as Android's recognizer, which the call relies on.
    _state.value = VoiceInputState.Idle
    _transcripts.tryEmit(text)
  }

  private fun fail(reason: VoiceInputState.ErrorReason) {
    _state.value = VoiceInputState.Error(reason)
  }

  private companion object {
    const val TAG = "MetaCallEars"

    /** `languageBias` takes language NAMES; only English is ever sent here. */
    fun languageNameOf(bcp47: String): String? = if (bcp47.startsWith("en")) "English" else null
  }
}
