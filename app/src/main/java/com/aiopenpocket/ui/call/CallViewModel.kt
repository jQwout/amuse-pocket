package com.aiopenpocket.ui.call

// @trace voice-call-mode-54cb49

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ChatImages
import com.aiopenpocket.agent.core.llm.ReasoningEffort
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnSession
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.agent.loop.TurnTexts
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.voice.call.CALL_PROMPT
import com.aiopenpocket.voice.call.CallLoop
import com.aiopenpocket.voice.call.CallPhase
import com.aiopenpocket.voice.call.CallSpeaker
import com.aiopenpocket.voice.call.SentenceFeed
import com.aiopenpocket.voice.input.VoiceInputController
import com.aiopenpocket.voice.input.VoiceInputState
import com.aiopenpocket.voice.input.appLanguageToBcp47
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CallUiState(
  val phase: CallPhase = CallPhase.LISTENING,
  /** What the person is saying right now, as the recognizer hears it. */
  val heard: String? = null,
  /** The last phrase sent to the agent. */
  val said: String? = null,
  /** The agent's reply as it is being written, then as saved. */
  val reply: String = "",
  /** What the agent is doing between words: a tool progress line. */
  val activity: String? = null,
  /** Why the call is not listening, in words for the person; null while all is well. */
  val notice: String? = null,
  /** Listening stopped (errors in a row, or a long silence); a tap starts it again. */
  val micPaused: Boolean = false,
  /** The person muted the microphone with the button; only the button unmutes it. */
  val micMuted: Boolean = false,
  /** What the voice is reading right now, for the running caption. */
  val caption: CallSpeaker.Caption? = null,
  /** A photo taken or picked during the call; it goes with the next phrase, then is cleared. */
  val photoPath: String? = null,
  /** The photo is being copied and downscaled. */
  val photoLoading: Boolean = false,
  val needsMicPermission: Boolean = false,
  val pendingApproval: ToolApprovalRequest? = null,
  val title: String? = null,
)

/**
 * A voice call with the assistant: listen, send the phrase as a turn, read the reply aloud sentence by
 * sentence while it is written, listen again. [CallLoop] decides who has the floor; this class wires it
 * to the recognizer ([VoiceInputController], restarted after every phrase because Android's recognizer
 * hears one phrase per session), the voice ([CallSpeaker]) and the turn in [TurnManager].
 *
 * The turn is an ordinary chat turn of the same conversation, so everything said in the call is in the
 * chat afterwards. A call only adds a spoken-style prompt ([CALL_PROMPT]) and a low reasoning level.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class CallViewModel(
  private val context: Context,
  private val conversationRepository: ConversationRepository,
  private val turnManager: TurnManager,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val toolApprovalCoordinator: ToolApprovalCoordinator,
  private val voice: VoiceInputController,
  private val speaker: CallSpeaker,
) : ViewModel() {

  private val _state = MutableStateFlow(CallUiState())
  val state: StateFlow<CallUiState> = _state.asStateFlow()

  private var loop = CallLoop()
  private var feed = SentenceFeed()
  private var started = false
  private var ended = false
  private var micGranted = false
  /** The camera or the gallery is open over the call: nobody is talking to it, so it does not listen. */
  private var pickingPhoto = false
  /** The recognizer's and the voice's language, from Settings; null until read (no listening before it). */
  private var language: String? = null

  private val convIdFlow = MutableStateFlow<String?>(null)

  /** The session of a turn from before the current phrase; nothing from it is read or shown. */
  private var staleSession: TurnSession? = null

  /** A turn this call launched is under way and its end not handled yet. */
  private var awaitingReply = false

  /** The turn's session was seen, so its disappearing means the turn is over, not that it has not begun. */
  private var turnSeen = false
  private var launchedAt = 0L

  private var sendJob: Job? = null
  private var listenJob: Job? = null
  private var errorStreak = 0
  private var silentStarts = 0

  @OptIn(ExperimentalCoroutinesApi::class)
  fun start(conversationId: String) {
    if (started) return
    started = true
    viewModelScope.launch {
      val tag = appLanguageToBcp47(userPreferencesRepository.getLanguage())
      language = tag
      speaker.warmUp(tag)
      conversationId.ifBlank { null }?.let { bind(it) }
      syncMic()
    }
    viewModelScope.launch { voice.state.collect(::onVoiceState) }
    viewModelScope.launch { voice.transcripts.collect(::onUserSaid) }
    viewModelScope.launch { speaker.speaking.collect { now -> update { it.speaking(now) } } }
    viewModelScope.launch { speaker.caption.collect { caption -> _state.update { it.copy(caption = caption) } } }
    // Only this conversation's requests: TurnManager runs turns of several chats at once.
    viewModelScope.launch {
      combine(convIdFlow, toolApprovalCoordinator.pending) { id, pending ->
        pending.firstOrNull { it.conversationId == id }
      }.collect { request ->
        _state.update { it.copy(pendingApproval = request) }
        update { it.approval(request != null) }
      }
    }
    viewModelScope.launch {
      convIdFlow.filterNotNull()
        .flatMapLatest { turnManager.session(it) }
        .collectLatest(::follow)
    }
  }

  fun onMicPermission(granted: Boolean) {
    micGranted = granted
    _state.update {
      it.copy(
        needsMicPermission = !granted,
        notice = if (granted) null else tr("Без микрофона звонок не услышит тебя", "The call can't hear you without the microphone"),
      )
    }
    syncMic()
  }

  /** A tap on the call screen: cut the agent off and take the floor, or wake a paused microphone. */
  fun tap() {
    when {
      loop.phase == CallPhase.SPEAKING || loop.phase == CallPhase.THINKING -> {
        speaker.stop()
        update { it.cutOff() }
      }
      _state.value.micPaused -> {
        errorStreak = 0
        silentStarts = 0
        _state.update { it.copy(micPaused = false, notice = null) }
        syncMic()
      }
    }
  }

  /**
   * The mute button. Muted, the call never listens, whoever has the floor; the agent still answers and
   * speaks. Unmuting starts afresh, as a tap on a paused microphone does.
   */
  fun toggleMute() {
    val muted = !_state.value.micMuted
    errorStreak = 0
    silentStarts = 0
    _state.update { it.copy(micMuted = muted, micPaused = false, heard = null, notice = null) }
    syncMic()
  }

  /**
   * Reads the agent's last reply aloud again (a tap on the reply, or the repeat button). Not while a turn
   * is still being read as it is written: the rest of it would be read on top.
   */
  fun replay() {
    val reply = _state.value.reply
    if (reply.isBlank() || (loop.turnRunning && loop.readsReply)) return
    speaker.stop()
    say(reply)
  }

  /** The camera or the gallery is about to open over the call. */
  fun onPhotoPickerOpening() {
    pickingPhoto = true
    syncMic()
  }

  /**
   * The camera or the gallery closed; [uri] is the photo, or null when the person backed out. The photo is
   * copied and downscaled like a chat attachment (ChatImages) and goes with the next phrase.
   */
  fun onPhotoPicked(uri: Uri?) {
    pickingPhoto = false
    syncMic()
    if (uri == null) return
    _state.update { it.copy(photoLoading = true) }
    viewModelScope.launch {
      val path = withContext(Dispatchers.IO) { ChatImages.import(context, uri) }
      _state.update {
        it.copy(
          photoLoading = false,
          photoPath = path ?: it.photoPath,
          notice = if (path == null) tr("Не получилось открыть это фото", "Couldn't open that photo") else it.notice,
        )
      }
    }
  }

  fun removePhoto() {
    _state.update { it.copy(photoPath = null) }
  }

  fun resolveApproval(approved: Boolean) {
    val request = _state.value.pendingApproval ?: return
    toolApprovalCoordinator.resolve(request.runId, request.requestId, approved)
  }

  /**
   * Ends the call and returns the conversation to open, if there is one yet. A turn still running is
   * left to finish: its reply lands in the chat as any other.
   */
  fun hangUp(): String? {
    ended = true
    listenJob?.cancel()
    voice.cancel()
    speaker.stop()
    return convIdFlow.value
  }

  override fun onCleared() {
    voice.release()
    speaker.release()
  }

  private suspend fun bind(id: String) {
    convIdFlow.value = id
    val title = runCatching { conversationRepository.getConversation(id)?.title }.getOrNull()
    _state.update { it.copy(title = title) }
  }

  // ---- the floor ----

  private fun update(transform: (CallLoop) -> CallLoop) {
    val next = transform(loop)
    if (next == loop) return
    loop = next
    _state.update { it.copy(phase = loop.phase) }
    syncMic()
  }

  private fun micWanted(): Boolean =
    started && !ended && micGranted && language != null && !_state.value.micPaused && !_state.value.micMuted &&
      !pickingPhoto && loop.wantsMic

  /** Turns the microphone on or off to match the floor. Called after every change that can affect it. */
  private fun syncMic() {
    val on = voice.state.value is VoiceInputState.Listening
    when {
      micWanted() && !on -> scheduleListen(RESTART_DELAY_MS)
      !micWanted() -> {
        listenJob?.cancel()
        if (on) voice.cancel()
      }
    }
  }

  /**
   * Starts the recognizer after [delayMs]. Right after a result the recognizer is still winding down
   * and answers a new start with "busy", hence the pause; a start already scheduled (an error backoff)
   * is kept.
   */
  private fun scheduleListen(delayMs: Long) {
    if (listenJob?.isActive == true) return
    listenJob = viewModelScope.launch {
      delay(delayMs)
      if (!micWanted() || voice.state.value is VoiceInputState.Listening) return@launch
      // Listen again, but not forever: every start beeps, and nobody has said a word for a while. Counted
      // here rather than on "no match" because some recognizers end a silent session with an empty result.
      if (++silentStarts > MAX_SILENT_STARTS) {
        pauseMic(tr("Я на паузе. Коснись экрана, чтобы говорить", "I've paused. Tap the screen to talk"))
        return@launch
      }
      voice.start(language ?: return@launch)
    }
  }

  private fun pauseMic(why: String) {
    listenJob?.cancel()
    _state.update { it.copy(micPaused = true, notice = why, heard = null) }
  }

  // ---- the person ----

  private fun onVoiceState(state: VoiceInputState) {
    when (state) {
      is VoiceInputState.Listening -> {
        if (state.partial != null) {
          errorStreak = 0
          silentStarts = 0
        }
        _state.update { it.copy(heard = state.partial) }
      }
      VoiceInputState.Idle -> syncMic()
      is VoiceInputState.Error -> onRecognizerError(state.reason)
    }
  }

  private fun onRecognizerError(reason: VoiceInputState.ErrorReason) {
    if (ended) return
    when (reason) {
      // Silence: just listen again (scheduleListen keeps count).
      VoiceInputState.ErrorReason.NoMatch -> scheduleListen(RESTART_DELAY_MS)
      VoiceInputState.ErrorReason.Permission -> onMicPermission(false)
      VoiceInputState.ErrorReason.Unavailable ->
        pauseMic(tr("Распознавание речи недоступно на этом телефоне", "Speech recognition isn't available on this phone"))
      else ->
        if (++errorStreak >= MAX_ERRORS) {
          pauseMic(
            if (reason == VoiceInputState.ErrorReason.Network) {
              tr("Нет сети для распознавания речи. Коснись, чтобы попробовать ещё", "No network for speech recognition. Tap to try again")
            } else {
              tr("Микрофон не отвечает. Коснись, чтобы попробовать ещё", "The microphone isn't responding. Tap to try again")
            },
          )
        } else {
          scheduleListen(ERROR_BACKOFF_MS * errorStreak)
        }
    }
  }

  private fun onUserSaid(text: String) {
    if (ended || text.isBlank()) return
    speaker.stop()
    feed = SentenceFeed()
    errorStreak = 0
    silentStarts = 0
    val photo = _state.value.photoPath
    _state.update { it.copy(said = text, heard = null, reply = "", activity = null, notice = null, photoPath = null) }
    update { it.userSaid() }
    sendJob?.cancel()
    sendJob = viewModelScope.launch {
      try {
        val id = convIdFlow.value ?: createConversation(text)
        // Drop the conversation's previous turn first (a chat's, or this call's own that was cut off),
        // so whatever the session shows from here on is this phrase's turn. Without it a reused session
        // could still hold the last reply, and the call would read it again as the answer.
        staleSession = turnManager.session(id).first()
        turnManager.cancelAndAwait(id)
        awaitingReply = true
        turnSeen = false
        launchedAt = System.currentTimeMillis()
        turnManager.launchTurn(
          id, text, AgentMode.CHAT, systemPromptExtra = CALL_PROMPT, imagePath = photo, effort = ReasoningEffort.LOW,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        android.util.Log.e(TAG, "call send failed", e)
        awaitingReply = false
        say(tr("Не получилось отправить. Повтори, пожалуйста.", "I couldn't send that. Please say it again."))
        update { it.turnEnded() }
      }
    }
  }

  private suspend fun createConversation(firstPhrase: String): String {
    // The persona from Settings, as in a chat started from the composer (ChatViewModel.ensureConversation).
    val id = conversationRepository.createConversation(
      title = ConversationRepository.placeholderTitle(firstPhrase),
      personaId = userPreferencesRepository.getDefaultPersonaId(),
    )
    bind(id)
    return id
  }

  // ---- the agent ----

  private suspend fun follow(session: TurnSession?) {
    if (session === staleSession) return
    if (session == null) {
      // The session went away (seen by the chat underneath, or stopped) before its end was handled here.
      if (awaitingReply && turnSeen) finishFromHistory()
      return
    }
    combine(session.status, session.streamingText, ::Pair).collect { (status, text) -> onTurn(status, text) }
  }

  private fun onTurn(status: TurnStatus, text: String) {
    if (!awaitingReply) return
    turnSeen = true
    when (status) {
      TurnStatus.Running -> onStream(text)
      // Handled in a job of its own: follow() is cancelled when the session changes, and an end handled
      // halfway would leave the call thinking forever.
      is TurnStatus.Completed -> {
        awaitingReply = false
        viewModelScope.launch { finish(status.insertedMessageId, status.error) }
      }
      is TurnStatus.Failed -> {
        awaitingReply = false
        _state.update { it.copy(reply = status.message, activity = null) }
        if (loop.readsReply) say(status.message)
        update { it.turnEnded() }
      }
    }
  }

  private fun onStream(text: String) {
    if (text.isBlank()) return
    if (TurnTexts.isToolProgress(text)) {
      _state.update { it.copy(activity = text) }
      return
    }
    _state.update { it.copy(reply = text, activity = null) }
    if (loop.readsReply) feed.onStream(text)?.let(::say)
  }

  private suspend fun finish(insertedMessageId: String?, error: String?) {
    val id = convIdFlow.value
    val saved = if (insertedMessageId != null && id != null) {
      runCatching {
        conversationRepository.getMessagesWindow(id, RECENT_WINDOW).first().firstOrNull { it.id == insertedMessageId }?.content
      }.getOrNull()
    } else {
      null
    }
    speakFinal(error ?: saved)
  }

  private suspend fun finishFromHistory() {
    awaitingReply = false
    val id = convIdFlow.value
    val reply = id?.let {
      runCatching {
        conversationRepository.getMessagesWindow(it, RECENT_WINDOW).first()
          .filter { m -> m.role == "assistant" && m.createdAt >= launchedAt }
          .maxByOrNull { m -> m.createdAt }
          ?.content
      }.getOrNull()
    }
    speakFinal(reply)
  }

  private fun speakFinal(text: String?) {
    if (!text.isNullOrBlank()) {
      _state.update { it.copy(reply = text, activity = null) }
      if (loop.readsReply) feed.onFinal(text)?.let(::say)
    }
    update { it.turnEnded() }
  }

  /** Reads [piece] and hands the floor to the voice. The engine reports it at once, on this thread. */
  private fun say(piece: String) {
    if (ended) return
    speaker.say(piece)
    update { it.speaking(speaker.speaking.value) }
  }

  private companion object {
    const val TAG = "CallViewModel"
    const val RESTART_DELAY_MS = 250L
    const val ERROR_BACKOFF_MS = 700L
    const val MAX_ERRORS = 4

    /** About a minute of nobody talking: a recognizer session gives up after ~5–8 s of silence. */
    const val MAX_SILENT_STARTS = 8

    /** Enough to find the turn's reply: the phrase and a reply plus its card messages. */
    const val RECENT_WINDOW = 6
  }
}
