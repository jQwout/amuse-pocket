package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonChatController
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.lesson.CAMERA_ORIGINAL_MAX_AGE_MS
import com.aiopenpocket.agent.core.lesson.PreparedPhoto
import com.aiopenpocket.agent.core.lesson.RESULT_COPY_KEEP
import com.aiopenpocket.agent.core.lesson.RESULT_COPY_MAX_AGE_MS
import com.aiopenpocket.agent.core.lesson.aboutMeFrom
import com.aiopenpocket.agent.core.lesson.prepareSegmentPhoto
import com.aiopenpocket.agent.core.lesson.sweepPhotoFolder
import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.execution.PlanCommands
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.agent.tools.HeartbeatTool
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.components.LESSON_PHOTO_DIR
import com.aiopenpocket.ui.chat.components.LESSON_SEGMENT_DIR
import com.aiopenpocket.ui.chat.components.bundledAssetPath
import com.aiopenpocket.voice.input.VoiceInputController
import com.aiopenpocket.voice.input.VoiceInputState
import com.aiopenpocket.voice.input.appLanguageToBcp47
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val turnManager: TurnManager,
    private val planCommands: PlanCommands,
    private val planRepository: PlanRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val lessonRepository: LessonRepository,
    private val lessonChatController: LessonChatController,
    private val memoryRepository: MemoryRepository,
    private val voiceController: VoiceInputController,
    private val heartbeatDao: HeartbeatDao,
    private val agentResolver: AgentResolver,
    private val toolApprovalCoordinator: ToolApprovalCoordinator,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // The input text is a SEPARATE flow (not a _state field), on the same principle as streamingText below:
    // otherwise every keystroke would change the state's identity and recompose the WHOLE chat body
    // (feed/overlays), not only the composer. It is read in a narrow scope at the input field in ChatScreen.
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /** The current conversationId for subscriptions to the turn session (empty = the chat has not been created yet). */
    private val convIdFlow = MutableStateFlow("")
    private var sessionJob: Job? = null

    // The streaming text is taken straight from the turn session in TurnManager: the turn (and its stream)
    // lives in appScope, and this flow only switches to the current conversation's session — come back to
    // the screen and the stream keeps running. A separate flow (not a _state field), so frequent token
    // deltas recompose only the stream bubble, not the whole screen.
    @OptIn(ExperimentalCoroutinesApi::class)
    val streamingText: StateFlow<String> = convIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else turnManager.session(id) }
        .flatMapLatest { session -> session?.streamingText ?: flowOf("") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Feed pagination: we show the last [windowLimit] messages and load "older" ones by raising the limit.
    // The message flow is rebuilt through flatMapLatest whenever the limit changes.
    private val windowLimit = MutableStateFlow(INITIAL_WINDOW)
    private var olderLoadInFlight = false

    /** The search query from the chat header; the match scan is debounced and runs off Main (see init). */
    private val searchQueryFlow = MutableStateFlow("")

    private var messagesJob: Job? = null

    // The active turn, the retry context and the stream handoff moved to TurnSession/TurnManager (appScope):
    // a turn no longer dies with the screen, and "Retry" survives the ViewModel being recreated.

    /** A guard against re-entry on a lesson step (a quick double tap on "Next"/a quiz). */
    private var lessonBusy = false

    init {
        // The lesson's SAM step leaves photos in cacheDir (camera originals, compressed copies for the result cards).
        // Whatever is old goes whenever a chat opens, so a learner's photo does not stay for good.
        viewModelScope.launch(Dispatchers.IO) { sweepLessonPhotos() }
        // The goal-mode iteration limit from settings — kept in the state to be passed to the orchestrator.
        viewModelScope.launch {
            userPreferencesRepository.goalModeMaxIterations.collect { n ->
                _state.update { it.copy(goalMaxIterations = n) }
            }
        }
        // The chat font size from settings — applied to the bubbles' text; changes on the fly.
        viewModelScope.launch {
            userPreferencesRepository.chatFontScale.collect { scale ->
                _state.update { it.copy(chatFontScale = scale) }
            }
        }
        viewModelScope.launch {
            voiceController.state.collect { vs ->
                _state.update {
                    it.copy(
                        voiceState = vs,
                        voiceError = (vs as? VoiceInputState.Error)?.reason?.toUserMessage(),
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceController.transcripts.collect { transcript ->
                _inputText.update { appendTranscript(it, transcript) }
            }
        }
        // The tool-approval bar (Task 9): the coordinator publishes pending requests GLOBALLY (for all
        // conversations at once, since TurnManager runs turns in parallel) — we filter on OUR OWN
        // conversationId so that another chat's approval never surfaces on this screen. combine (not just
        // .collect on pending) — because conversationId changes too (setConversationId/ensureConversation).
        viewModelScope.launch {
            combine(convIdFlow, toolApprovalCoordinator.pending) { convId, pending ->
                pending.firstOrNull { it.conversationId == convId }
            }.collect { request ->
                _state.update { it.copy(pendingToolApproval = request) }
            }
        }
        // Task 10 (structured-plan-engine-907066): the plan approval bar comes reactively from PlanRepository,
        // not from turn events (see the observePendingPlanApproval kdoc).
        observePendingPlanApproval()
        // Task 12: the live plan progress bar in the chat — the same reactive subscription, but for
        // ACTIVE/PAUSED instead of AWAITING_APPROVAL (see the observeActivePlan kdoc).
        observeActivePlan()
        // Chat search: the input is debounced (collectLatest + delay — a new character cancels the tail) and the
        // contains scan runs on Default. The scan used to run right in composition on every typed character
        // — on a long history that is tens of ms on Main and dropped frames. contains(ignoreCase) in Kotlin
        // folds Cyrillic correctly (SQL LIKE does not), so the search runs in memory, not in SQL.
        viewModelScope.launch {
            combine(
                searchQueryFlow,
                _state.map { it.messages }.distinctUntilChanged(),
            ) { query, messages -> query to messages }
                .collectLatest { (query, messages) ->
                    if (query.isBlank()) {
                        if (_state.value.searchMatches.isNotEmpty()) {
                            _state.update { it.copy(searchMatches = emptyList()) }
                        }
                        return@collectLatest
                    }
                    delay(SEARCH_DEBOUNCE_MS)
                    val matches = withContext(Dispatchers.Default) {
                        messages.mapIndexedNotNull { index, message ->
                            if (message.content.contains(query, ignoreCase = true)) index else null
                        }
                    }
                    _state.update { it.copy(searchMatches = matches) }
                }
        }
    }

    /**
     * Task 10 (structured-plan-engine-907066): reactive derivation of `ChatUiState.pendingPlanApproval`
     * straight from [PlanRepository.observeByConversation] — NOT from any `AgentEvent`
     * `StructuredPlanOrchestrator` emits. This is what actually raises `ChatScreen`'s plan card
     * (Task 5 built the original bar gated on this field but nothing ever populated it until this
     * task; Task 12 later replaced that bar with `com.aiopenpocket.ui.plan.PlanCard`).
     * Reacting to the repository directly (rather than a one-shot turn event) means: re-opening a
     * conversation that already has a pending plan shows the bar immediately, and any OTHER path that
     * ever calls [PlanRepository.saveProposal] for this conversation (not just this screen's own PLAN
     * turn) is picked up the same way.
     *
     * `distinctUntilChanged` (on the DERIVED [PendingPlanApproval]?, not the raw snapshot list) is
     * what lets [ChatIntent.EditPlan]'s local-only dismiss (`pendingPlanApproval = null` without
     * touching the repository — see its own kdoc) stick until the pending plan/version genuinely
     * changes: Room's per-TABLE invalidation would otherwise re-emit (and re-show the bar) on ANY
     * unrelated write to the plan tables, even one for a different plan entirely.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePendingPlanApproval() {
        viewModelScope.launch {
            convIdFlow.flatMapLatest { id ->
                if (id.isBlank()) flowOf(emptyList()) else planRepository.observeByConversation(id)
            }.map { snapshots ->
                snapshots.filter { it.plan.status == PlanStatus.AWAITING_APPROVAL }
                    .maxByOrNull { it.plan.updatedAt }
                    ?.let { PendingPlanApproval(it.plan.id, it.version.version) }
            }.distinctUntilChanged().collect { pending ->
                _state.update { it.copy(pendingPlanApproval = pending) }
            }
        }
    }

    /**
     * Task 12 (structured-plan-engine-907066): reactive derivation of `ChatUiState.activePlanId` —
     * mirrors [observePendingPlanApproval] but picks the conversation's ACTIVE/PAUSED plan instead of
     * its AWAITING_APPROVAL one, so `PlanCard` in the chat stream can keep showing live progress once
     * the plan is approved — execution starts right away, in the background (see [runPlanCommand]) — or
     * GOAL advances it (a case [pendingPlanApproval] never covers, since it
     * only ever holds a plan awaiting a decision). Only the plan id is kept in state — the card itself
     * subscribes to the full [PlanSnapshot] via [observePlan], the same "card owns its own live
     * subscription" shape as `ReminderCardView`/[observeHeartbeat].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActivePlan() {
        viewModelScope.launch {
            convIdFlow.flatMapLatest { id ->
                if (id.isBlank()) flowOf(emptyList()) else planRepository.observeByConversation(id)
            }.map { snapshots ->
                snapshots.filter { it.plan.status == PlanStatus.ACTIVE || it.plan.status == PlanStatus.PAUSED }
                    .maxByOrNull { it.plan.updatedAt }
                    ?.plan?.id
            }.distinctUntilChanged().collect { activePlanId ->
                _state.update { it.copy(activePlanId = activePlanId) }
            }
        }
    }

    override fun onCleared() {
        voiceController.release()
        super.onCleared()
    }

    fun setConversationId(id: String) {
        if (_state.value.conversationId == id) return
        // The turn is NOT cancelled — it lives in TurnManager (appScope) and finishes in the background; only the
        // screen's subscriptions and screen state are reset.
        messagesJob?.cancel()
        sessionJob?.cancel()
        lessonBusy = false
        // The pref-derived fields (font/iteration limit) are carried over into the new state — otherwise resetFor
        // would reset them to the default, and the reactive collector from DataStore does not re-emit an
        // unchanged value, so the setting would be stuck at the default.
        _state.update { prev -> ChatUiState.resetFor(id, prev.chatFontScale, prev.goalMaxIterations) }
        _inputText.value = "" // the input draft is not carried between conversations (resetFor used to do this)
        convIdFlow.value = id
        // A new chat (empty id): the conversation does not exist yet, ensureConversation creates it on send.
        // The collector for getMessages("") is always empty — it is not started.
        if (id.isBlank()) return
        observeMessages(id)
        observeSession(id)
        // Entering the conversation clears the "done" badge in the list (markSeen does not touch Running/an error).
        turnManager.markSeen(id)
        // A lesson conversation: interactive cards are enabled. If there is a JSON lesson for the title, we switch
        // to lesson mode and drive the steps with the controller (a strict contract), otherwise the free path
        // (l2–l4). The agent mode (plan/goal) is NOT enabled automatically — only by an explicit choice in
        // the UI.
        viewModelScope.launch {
            val conversation = conversationRepository.getConversation(id)
            val tutorial = conversation?.isTutorial == true
            val title = conversation?.title
            // The full snapshot (not just the name) is resolved once here — ChatScreen reads state.agent directly
            // instead of going to MiniAgentCatalog/AgentResolver again in composition.
            val agent = conversation?.agentId?.let { agentId -> agentResolver.resolve(agentId) }
            // l1_welcome is excluded: the controller cannot do its setup steps (language/persona/profile) —
            // onboarding goes through its own screen, not through the chat driver.
            val lesson = if (tutorial && title != null && title != ONBOARDING_LESSON_ID) {
                lessonRepository.getLessonOrNull(title)
            } else {
                null
            }
            _state.update {
                it.copy(
                    isTutorial = tutorial,
                    levelId = if (tutorial) title else null,
                    agentId = conversation?.agentId,
                    agent = agent,
                    // A lesson's conversation title is its id (that is how the lesson is found), while the header needs
                    // the human title: otherwise it would show "tokens_and_speed".
                    title = agent?.displayName ?: lesson?.title?.takeIf { it.isNotBlank() } ?: title,
                    lessonMode = lesson != null,
                    lesson = lesson,
                    // The default mode from the agent's config. Until now nobody read the builder's field except the test
                    // run in the wizard itself: the chat always opened in CHAT, and a "GOAL-only agent"
                    // behaved like a plain chat. Set once when the conversation opens (resetFor above has
                    // already reset the state); after that the user's choice decides.
                    agentMode = agent?.defaultMode ?: it.agentMode,
                )
            }
            if (lesson != null) startOrResumeLesson(id, lesson)
        }
    }

    /**
     * Starts the lesson (publishes step 0) or restores the position after reopening. The cursor is
     * explicit conversation state (lessonStepIndex), not a count of assistant messages: that way a free
     * question to the LLM during a lesson does not move the step (and deleting a message no longer
     * desyncs the lesson).
     */
    private suspend fun startOrResumeLesson(id: String, lesson: com.aiopenpocket.agent.core.lesson.Lesson) {
        // The cursor comes from the conversation's EXPLICIT state (lessonStepIndex), not from the number of
        // assistant messages: otherwise a free question to the LLM during a lesson (an extra answer) would
        // move the step. See LessonCursorPersistenceTest.
        val conversation = conversationRepository.getConversation(id)
        val persistedIndex = conversation?.lessonStepIndex ?: 0
        val persistedFinished = conversation?.lessonFinished ?: false
        if (!persistedFinished) {
            // In a separate coroutine: Muse Image takes tens of seconds to draw, and the lesson's first step must not
            // wait for it. Steps already passed are not drawn — their cards are already in the history.
            viewModelScope.launch { lessonChatController.prefetchImages(lesson, fromIndex = persistedIndex) }
        }

        if (lesson.steps.isEmpty()) {
            // A lesson without steps: a completion card so the screen does not hang empty — but no duplicates on reopening.
            if (!persistedFinished) {
                lessonChatController.postCompletion(id, lesson)
                conversationRepository.updateLessonCursor(id, stepIndex = 0, finished = true)
            }
            _state.update { it.copy(lessonStepIndex = 0, lessonFinished = true) }
            return
        }
        if (persistedIndex == 0 && !persistedFinished) {
            // The lesson has not started: publish the first step and set the cursor = 1. Self-heal: if step 0 is
            // already in the history (a crash between the post and the cursor write), do not post it again —
            // only fix the cursor, so no duplicate of the first step appears.
            val alreadyPosted = conversationRepository.getAllMessages(id).any { it.role == "assistant" }
            if (!alreadyPosted) lessonChatController.postStep(id, lesson, 0)
            conversationRepository.updateLessonCursor(id, stepIndex = 1, finished = false)
            _state.update { it.copy(lessonStepIndex = 1, lessonFinished = false) }
        } else {
            // Resuming: the steps are already in the history, we just restore the saved cursor.
            _state.update {
                it.copy(
                    lessonStepIndex = persistedIndex.coerceAtMost(lesson.steps.size),
                    lessonFinished = persistedFinished || persistedIndex > lesson.steps.size,
                )
            }
        }
    }

    /** Sends [text] into the conversation as a user message (used by interactive cards). */
    fun sendUserMessage(text: String) {
        if (text.isBlank()) return
        _inputText.value = text
        sendMessage()
    }

    /** Marks the current lesson complete and invokes [onComplete] (e.g. navigate back). */
    fun completeLesson(onComplete: () -> Unit) {
        val levelId = _state.value.levelId ?: return
        viewModelScope.launch {
            userPreferencesRepository.completeRoadmapLevel(levelId)
            onComplete()
        }
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> { _inputText.value = intent.text }
            is ChatIntent.SendMessage -> sendMessage()
            is ChatIntent.CancelRequest -> cancel()
            is ChatIntent.RetryRequest -> retry()
            is ChatIntent.ToggleToolTrace -> _state.update { it.copy(showToolTrace = !it.showToolTrace) }
            is ChatIntent.DeleteMessage -> deleteMessage(intent.messageId)
            is ChatIntent.ClearError -> {
                currentConversationId()?.let { turnManager.clearError(it) }
                _state.update { it.copy(error = null, canRetry = false) }
            }
            is ChatIntent.NavigateBack -> { /* handled by navigation */
            }

            is ChatIntent.ToggleVoiceInput -> toggleVoiceInput()
            is ChatIntent.CancelVoiceInput -> voiceController.cancel()
            is ChatIntent.ClearVoiceError -> {
                voiceController.clearError()
                _state.update { it.copy(voiceError = null) }
            }
            // Changing the mode invalidates the previous turn's "Retry" (or the retry would go to the old mode).
            // pendingPlanApproval is NOT touched: that is plan state in PlanRepository, not chat mode state.
            // The chip used to reset it, and the derived flow (distinctUntilChanged) did not send it again —
            // the only approval card disappeared until the user re-entered the conversation.
            is ChatIntent.SetMode -> {
                currentConversationId()?.let { turnManager.invalidateRetry(it) }
                _state.update { it.copy(agentMode = intent.mode, canRetry = false) }
            }
            is ChatIntent.ApprovePlan -> approvePlanCommand(intent.planId, intent.version)
            is ChatIntent.RequestPlanChanges -> requestPlanChanges(intent.planId, intent.version, intent.feedback)
            is ChatIntent.PausePlan -> runPlanCommand { planCommands.pause(intent.planId) }
            is ChatIntent.ResumePlan -> runPlanCommand { planCommands.resume(intent.planId) }
            is ChatIntent.CancelPlan -> runPlanCommand { planCommands.cancel(intent.planId) }
            is ChatIntent.ConfirmPlanStep -> runPlanCommand { planCommands.confirmStep(intent.planId, intent.stepId) }
            // "Edit" is a purely local gesture (see the ChatIntent.EditPlan kdoc): it hides the bar and does not touch PlanRepository.
            is ChatIntent.EditPlan -> _state.update { it.copy(pendingPlanApproval = null) }
            is ChatIntent.AdvanceLesson -> advanceLesson()
            is ChatIntent.CompleteLessonQuiz -> completeLessonQuiz()
            is ChatIntent.SaveCardState -> viewModelScope.launch {
                conversationRepository.updateMessageCardState(intent.messageId, intent.state)
            }
            is ChatIntent.PersonalizeLessonImage -> personalizeLessonImage(intent.imagePrompt, intent.caption)
            is ChatIntent.AnalyzeLessonVideo -> analyzeLessonVideo(intent.videoPrompt, intent.video)
            is ChatIntent.SegmentLessonPhoto -> segmentLessonPhoto(intent.cardPrompt, intent.concept, intent.photo)
            is ChatIntent.AskDuringLesson -> askDuringLesson(intent.text)
            is ChatIntent.ApproveToolCall -> resolveToolApproval(approved = true)
            is ChatIntent.DenyToolCall -> resolveToolApproval(approved = false)
        }
    }

    /**
     * Resolves `state.pendingToolApproval` (Task 9) — a no-op if it's already gone (timed out,
     * resolved by a background signal, or the run moved on). `resolve` itself checks the request's
     * own runId, so a stale request left over from an earlier, already-finished run of this same
     * conversation can never be (mis)approved by a leftover reference here.
     */
    private fun resolveToolApproval(approved: Boolean) {
        val request = _state.value.pendingToolApproval ?: return
        toolApprovalCoordinator.resolve(request.runId, request.requestId, approved)
    }

    private fun toggleVoiceInput() {
        when (_state.value.voiceState) {
            is VoiceInputState.Listening -> voiceController.stop()
            else -> viewModelScope.launch {
                val tag = appLanguageToBcp47(userPreferencesRepository.getLanguage())
                voiceController.start(tag)
            }
        }
    }

    private fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        _inputText.value = ""
        viewModelScope.launch {
            try {
                // The "chat/new" route gives an empty id — the conversation is created BEFORE launchTurn: the session is keyed by id.
                val convId = ensureConversation(text)
                // Task 10 (structured-plan-engine-907066): planId=null — StructuredPlanOrchestrator resolves the
                // conversation's plan itself (PLAN proposes/revises the current one; GOAL continues an ACTIVE
                // one or proposes a new one if there is no approved plan yet). goalMaxIterations from settings
                // maps onto maxLlmIterationsPerStep — the same "reasoning budget", just now per ONE plan step.
                turnManager.launchTurn(convId, text, _state.value.agentMode, budget = goalRunBudget(_state.value.goalMaxIterations))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "send failed", e)
                _state.update { it.copy(error = e.message, canRetry = false) }
            }
        }
    }

    /**
     * Task 10 (structured-plan-engine-907066): maps the user's "goal-mode iteration limit" setting
     * (`ChatUiState.goalMaxIterations`, from [com.aiopenpocket.agent.core.identity.UserPreferencesRepository])
     * onto [PlanRunBudget.maxLlmIterationsPerStep] — the closest surviving analogue now that a whole
     * GOAL turn is no longer one bounded ReAct loop but a run over one plan STEP at a time. The
     * Settings screen/repository for this value are unchanged; only where it lands downstream moved.
     */
    private fun goalRunBudget(maxIterations: Int) = PlanRunBudget(maxLlmIterationsPerStep = maxIterations)

    /**
     * Task 5 (structured-plan-engine-907066): typed plan commands go straight to [PlanCommands] —
     * no magic chat message. Approval STARTS execution: [PlanCommands.approve] calls
     * `PlanScheduleSync.sync`, which right away arms a heartbeat for the first unscheduled step — the steps
     * run in the background (HeartbeatRunner), and their answers are written into this same chat. A GOAL
     * turn can still advance the plan manually. "Ask for changes" ([requestPlanChanges]) is the only
     * command that starts a turn (PLAN) itself right after, otherwise a new plan version would never
     * appear. [runPlanCommand] mirrors
     * [sendMessage]'s own cancellation/error handling so a rejected command (e.g. approving a
     * superseded version — see `PlanRepository.approve`) surfaces the same way any other action
     * failure does on this screen, instead of crashing the ViewModel's scope.
     */
    private fun runPlanCommand(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "plan command failed", e)
                // e.message here is engine checks like "Cannot apply Pause to plan …" in English; a person needs one
                // clear line, the details go to the log.
                _state.update { it.copy(error = tr("Не удалось выполнить действие с планом — попробуй ещё раз.", "Couldn't complete the plan action — try again.")) }
            }
        }
    }

    private fun approvePlanCommand(planId: String, version: Int) = runPlanCommand {
        planCommands.approve(planId, version)
        _state.update { it.copy(pendingPlanApproval = null) }
    }

    /**
     * "Ask for changes": the plan goes to DRAFT, and right away a PLAN turn runs in this conversation with
     * the requested changes (or with [DEFAULT_PLAN_REVISION_REQUEST] if the field is empty), so the planner
     * proposes a new version. The command used to only move the plan to DRAFT: the feedback was saved
     * nowhere (PlanRepository.requestRevision does not store it), the card disappeared, and nothing
     * happened. The feedback goes as a user message — so it is both visible in the chat and part of the
     * planner's prompt.
     */
    private fun requestPlanChanges(planId: String, version: Int, feedback: String) = runPlanCommand {
        planCommands.requestChanges(planId, version, feedback)
        _state.update { it.copy(pendingPlanApproval = null) }
        val convId = currentConversationId() ?: return@runPlanCommand
        val request = feedback.trim().ifEmpty { DEFAULT_PLAN_REVISION_REQUEST }
        turnManager.launchTurn(convId, request, AgentMode.PLAN)
    }

    /** "Next" on text and passing a quiz both advance the lesson cursor to the next step the same way. */
    private fun advanceLesson() = advanceLessonCursor()

    private fun completeLessonQuiz() = advanceLessonCursor()

    /**
     * A free question to the LLM during a lesson: a tool-less TUTOR turn in the same conversation
     * ([AgentMode.TUTOR] only explains, without tools). The tutor frame comes from systemPromptSuffix, and
     * the current step's context is injected through systemPromptExtra (card steps are written with
     * content="" and do NOT get into the history, see LessonQuestion + the review finding). The lesson
     * cursor is persisted separately (lessonStepIndex), so an extra assistant answer does NOT move it (see
     * LessonCursorPersistenceTest).
     */
    private fun askDuringLesson(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        val s = _state.value
        if (!s.lessonMode || s.isLoading) return
        val convId = s.conversationId?.takeIf { it.isNotBlank() } ?: return
        val currentStep = s.lesson?.steps?.getOrNull(s.lessonStepIndex - 1)
        val stepContext = com.aiopenpocket.agent.core.lesson.LessonQuestion
            .stepContext(lessonTitle = s.lesson?.title.orEmpty(), step = currentStep)
            .takeIf { it.isNotBlank() }
        viewModelScope.launch {
            try {
                // TUTOR ignores planId/budget (it is dispatched to AgentLoopOrchestrator, not to
                // StructuredPlanOrchestrator) — they are left at their defaults.
                turnManager.launchTurn(convId, q, AgentMode.TUTOR, systemPromptExtra = stepContext)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "lesson ask failed", e)
                _state.update { it.copy(error = e.message, canRetry = false) }
            }
        }
    }

    /**
     * "Draw it for me" on a lesson's image step (see LessonChatController.postPersonalizedImage).
     *
     * First we check what the user can fix themselves, and say it plainly: without "about me" there is
     * nobody to draw for. Otherwise there would be a single "it did not work" for everything.
     */
    private fun personalizeLessonImage(imagePrompt: String, caption: String) {
        val convId = currentConversationId() ?: return
        if (imagePrompt.isBlank() || imagePrompt in _state.value.personalizingImagePrompts) return
        _state.update {
            it.copy(personalizingImagePrompts = it.personalizingImagePrompts + imagePrompt, error = null, canRetry = false, lessonStepProblem = null)
        }
        viewModelScope.launch {
            val problem = try {
                // The profile from Room, the language from DataStore — all on IO.
                val (aboutMe, language) = withContext(Dispatchers.IO) {
                    aboutMeFrom(memoryRepository.getFactsByCategory("profile")) to userPreferencesRepository.getLanguage()
                }
                when {
                    aboutMe.isBlank() -> tr("Чтобы нарисовать под тебя, расскажи о себе в профиле.", "To draw it for you, tell me about yourself in your profile.")
                    !lessonChatController.postPersonalizedImage(convId, imagePrompt, caption, aboutMe, language) ->
                        tr("Не получилось нарисовать. Проверь ключ OpenRouter и сеть и попробуй ещё раз.", "Couldn't draw it. Check your OpenRouter key and network, then try again.")
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "personalize lesson image failed", e)
                tr("Не получилось нарисовать. Попробуй ещё раз.", "Couldn't draw it. Try again.")
            } finally {
                _state.update { it.copy(personalizingImagePrompts = it.personalizingImagePrompts - imagePrompt) }
            }
            // The step's own slot, not the chat-wide error: see ChatUiState.lessonStepProblem.
            if (problem != null) _state.update { it.copy(lessonStepProblem = problem) }
        }
    }

    /**
     * The "video" lesson step: the user's clip → Muse Spark → an answer in the chat
     * (LessonChatController.postVideoAnswer).
     *
     * The clip is read with a ceiling — a long or 4K video is not loaded into memory whole.
     * A file filmed with the camera (ours, in cacheDir) is deleted on any outcome; one picked from the
     * gallery belongs to someone else and is not touched.
     */
    private fun analyzeLessonVideo(videoPrompt: String, video: android.net.Uri) {
        val convId = currentConversationId() ?: return
        if (videoPrompt.isBlank() || videoPrompt in _state.value.analyzingVideoPrompts) return
        _state.update {
            it.copy(analyzingVideoPrompts = it.analyzingVideoPrompts + videoPrompt, error = null, canRetry = false, lessonStepProblem = null)
        }
        viewModelScope.launch {
            val problem = try {
                val language = withContext(Dispatchers.IO) { userPreferencesRepository.getLanguage() }
                val clip = withContext(Dispatchers.IO) { readLessonVideo(video) }
                when {
                    clip == null -> tr("Видео слишком большое или не открылось — сними покороче, до 10 секунд.", "The video is too large or didn't open — record a shorter one, up to 10 seconds.")
                    !lessonChatController.postVideoAnswer(convId, videoPrompt, clip.first, clip.second, language) ->
                        tr("Не получилось разобрать видео. Проверь ключ OpenRouter и сеть и попробуй ещё раз.", "Couldn't analyze the video. Check your OpenRouter key and network, then try again.")
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "lesson video failed", e)
                tr("Не получилось разобрать видео. Попробуй ещё раз.", "Couldn't analyze the video. Try again.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { deleteOwnCapture(video) }
                _state.update { it.copy(analyzingVideoPrompts = it.analyzingVideoPrompts - videoPrompt) }
            }
            // The step's own slot, not the chat-wide error: see ChatUiState.lessonStepProblem.
            if (problem != null) _state.update { it.copy(lessonStepProblem = problem) }
        }
    }

    /** The clip's bytes and type, or null: it did not open or is longer than [VideoUnderstandingClient.MAX_VIDEO_BYTES]. */
    private fun readLessonVideo(video: android.net.Uri): Pair<ByteArray, String>? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(video)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val limit = com.aiopenpocket.agent.core.llm.VideoUnderstandingClient.MAX_VIDEO_BYTES
        // The lesson's sample clip lives in the APK's assets: the content resolver does not open file:///android_asset.
        val asset = video.bundledAssetPath()
        val stream = if (asset != null) context.assets.open(asset) else resolver.openInputStream(video)
        val bytes = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        } ?: return null
        return bytes.takeIf { it.isNotEmpty() }?.let { it to mimeType }
    }

    /** Deletes a clip filmed with the camera for a lesson: it is ours and lives in cacheDir. Gallery clips are not touched. */
    private fun deleteOwnCapture(video: android.net.Uri) {
        if (video.authority != "${context.packageName}.fileprovider") return
        val name = video.lastPathSegment ?: return
        java.io.File(java.io.File(context.cacheDir, com.aiopenpocket.ui.chat.components.LESSON_VIDEO_DIR), name).delete()
    }

    /**
     * The "segment" lesson step: the learner's photo -> SAM 3.1 -> a result card with frames under the step
     * (LessonChatController.postSegmentation).
     *
     * The key is checked FIRST: without a Meta key there is no point in decoding a photo, and "add a key in
     * Settings, or skip the step" is the one thing the learner can act on. The photo is prepared off the main
     * thread (decode, EXIF rotation, shrink — see prepareSegmentPhoto). A photo taken with our camera (it sits
     * in cacheDir) is deleted on any outcome; one from the gallery is not ours and is not touched. The
     * compressed copy stays only if the card was posted, because the card shows it.
     */
    private fun segmentLessonPhoto(cardPrompt: String, concept: String, photo: android.net.Uri) {
        val convId = currentConversationId()
        if (convId == null || cardPrompt.isBlank() || cardPrompt in _state.value.segmentingPrompts) {
            // Nothing will analyse this photo, and a file our own camera just wrote must not be left behind.
            viewModelScope.launch(Dispatchers.IO) { deleteOwnPhotoCapture(photo) }
            return
        }
        _state.update {
            it.copy(segmentingPrompts = it.segmentingPrompts + cardPrompt, error = null, canRetry = false, lessonStepProblem = null)
        }
        viewModelScope.launch {
            var copy: java.io.File? = null
            var posted = false
            val problem = try {
                if (!lessonChatController.segmentationReady()) {
                    segmentationProblemText(SegmentationFailure.NO_KEY)
                } else {
                    val ready = withContext(Dispatchers.IO) { prepareLessonPhoto(photo) }
                    copy = ready?.file
                    if (ready == null) {
                        tr("Не получилось открыть фото — выбери другое.", "Couldn't open the photo — pick another one.")
                    } else {
                        val failure = lessonChatController.postSegmentation(convId, concept, ready)
                        posted = failure == null
                        failure?.let(::segmentationProblemText)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "lesson segmentation failed", e)
                tr("Не получилось обвести объекты. Попробуй ещё раз.", "Couldn't outline the objects. Try again.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    deleteOwnPhotoCapture(photo)
                    // A card that was not posted has no use for its picture.
                    if (!posted) copy?.delete()
                    sweepLessonPhotos()
                }
                _state.update { it.copy(segmentingPrompts = it.segmentingPrompts - cardPrompt) }
            }
            // The step's own slot, not the chat-wide error: see ChatUiState.lessonStepProblem.
            if (problem != null) _state.update { it.copy(lessonStepProblem = problem) }
        }
    }

    /** The photo as the JPEG that goes to SAM, or null when it cannot be opened or is not a picture. Blocking: call on IO. */
    private fun prepareLessonPhoto(photo: android.net.Uri): PreparedPhoto? {
        val asset = photo.bundledAssetPath()
        val resolver = context.contentResolver
        return prepareSegmentPhoto(
            // A source that cannot be opened (a revoked gallery URI, a missing asset) is "no photo", not a crash.
            open = { runCatching { if (asset != null) context.assets.open(asset) else resolver.openInputStream(photo) }.getOrNull() },
            outputDir = java.io.File(context.cacheDir, LESSON_SEGMENT_DIR),
            name = "segment-${System.currentTimeMillis()}.jpg",
        )
    }

    /** Old photos of the SAM step go: orphaned camera originals after an hour, result-card copies after a day (and beyond the newest five). */
    private fun sweepLessonPhotos() {
        val now = System.currentTimeMillis()
        sweepPhotoFolder(java.io.File(context.cacheDir, LESSON_PHOTO_DIR), now, CAMERA_ORIGINAL_MAX_AGE_MS)
        sweepPhotoFolder(java.io.File(context.cacheDir, LESSON_SEGMENT_DIR), now, RESULT_COPY_MAX_AGE_MS, RESULT_COPY_KEEP)
    }

    /** Deletes a photo taken with the camera for a lesson: it is ours and lives in cacheDir. Gallery photos are not touched. */
    private fun deleteOwnPhotoCapture(photo: android.net.Uri) {
        if (photo.authority != "${context.packageName}.fileprovider") return
        val name = photo.lastPathSegment ?: return
        java.io.File(java.io.File(context.cacheDir, LESSON_PHOTO_DIR), name).delete()
    }

    /**
     * Publishes the next lesson step, or the completion card if there are no more steps. Thanks to this a
     * quiz works correctly even when it is NOT the last step (after the answer we just move on rather than
     * finish).
     */
    private fun advanceLessonCursor() {
        val s = _state.value
        val lesson = s.lesson ?: return
        val convId = s.conversationId?.takeIf { it.isNotBlank() } ?: return
        if (s.lessonFinished || lessonBusy) return
        lessonBusy = true
        viewModelScope.launch {
            try {
                if (s.lessonStepIndex < lesson.steps.size) {
                    lessonChatController.postStep(convId, lesson, s.lessonStepIndex)
                    val newIndex = s.lessonStepIndex + 1
                    conversationRepository.updateLessonCursor(convId, stepIndex = newIndex, finished = false)
                    _state.update { it.withLessonCursor(stepIndex = newIndex) }
                } else {
                    lessonChatController.postCompletion(convId, lesson)
                    conversationRepository.updateLessonCursor(convId, stepIndex = s.lessonStepIndex, finished = true)
                    _state.update { it.withLessonCursor(finished = true) }
                }
            } finally {
                lessonBusy = false
            }
        }
    }

    private fun retry() {
        currentConversationId()?.let { turnManager.retry(it) }
    }

    private fun currentConversationId(): String? =
        _state.value.conversationId?.takeIf { it.isNotBlank() }

    /**
     * Projects the turn session from TurnManager into the screen state. The turn lives in appScope — the
     * screen only reflects it. The end-of-turn "flicker" is solved by derivation: the bubble (isLoading) is
     * kept until the Completed message reaches the feed window — the swap is atomic, since messages and the
     * status meet in one combine and one _state.update.
     */
    private fun observeSession(convId: String) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            turnManager.session(convId).collectLatest { session ->
                if (session == null) {
                    // No session (there was no turn / it was cancelled / seen) — only clear the indication,
                    // the rest of the state (messages, statistics) is not touched.
                    _state.update { it.copy(isLoading = false) }
                    return@collectLatest
                }
                combine(
                    session.status,
                    session.toolTrace,
                    _state.map { it.messages }.distinctUntilChanged(),
                ) { status, trace, messages -> Triple(status, trace, messages) }
                    .collect { (status, trace, messages) ->
                        when (status) {
                            is TurnStatus.Running -> _state.update {
                                it.copy(isLoading = true, toolTrace = trace, error = null, canRetry = false)
                            }

                            is TurnStatus.Completed -> {
                                val arrived = status.insertedMessageId == null ||
                                    messages.any { it.id == status.insertedMessageId }
                                _state.update {
                                    it.copy(
                                        isLoading = !arrived,
                                        toolTrace = trace,
                                        error = status.error,
                                        canRetry = status.canRetry,
                                        lastResponseStats = ResponseStats(
                                            responseTimeMs = status.responseTimeMs,
                                            completionTokens = status.completionTokens,
                                            contextTokensUsed = status.contextTokensUsed,
                                        ),
                                    )
                                }
                                // The answer reached the feed — the clean session is no longer needed (clears the "done" badge
                                // in the list; markSeen itself will not touch an error/canRetry).
                                if (arrived && status.insertedMessageId != null) {
                                    turnManager.markSeen(convId)
                                }
                            }

                            is TurnStatus.Failed -> _state.update {
                                it.copy(isLoading = false, toolTrace = trace, error = status.message, canRetry = status.canRetry)
                            }
                        }
                    }
            }
        }
    }

    private suspend fun ensureConversation(firstMessage: String): String {
        _state.value.conversationId?.takeIf { it.isNotBlank() }?.let { return it }
        val title = ConversationRepository.placeholderTitle(firstMessage)
        // The persona from Settings, not the repository's default: chats started by the voice assistant come
        // this way (the chat/new route), and without it they would always speak as "Sharp partner".
        val newId = conversationRepository.createConversation(
            title = title,
            personaId = userPreferencesRepository.getDefaultPersonaId(),
        )
        _state.update { it.copy(conversationId = newId) }
        observeMessages(newId)
        observeSession(newId)
        convIdFlow.value = newId
        return newId
    }

    /** The reminder card's live subscription — see [ReminderCardView]. */
    // @trace heartbeat-napominalki-agenta-5f0442
    fun observeHeartbeat(id: String): Flow<HeartbeatEntity?> = heartbeatDao.observe(id)

    /** The live subscription of [com.aiopenpocket.ui.plan.PlanCard] — see its own kdoc "never trusts a field copied into a chat message". */
    // @trace structured-plan-engine-907066
    fun observePlan(planId: String): Flow<PlanSnapshot?> = planRepository.observe(planId)

    /** Cancel from the card: cancel the WorkManager job and record the status in Room (the card redraws itself). */
    // @trace heartbeat-napominalki-agenta-5f0442
    fun cancelHeartbeat(id: String) {
        viewModelScope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(HeartbeatTool.workName(id))
            heartbeatDao.setStatus(id, "cancelled")
        }
    }

    /** Deletes a message. `state.messages` is reactive (a Flow from the DB), so the feed updates itself. */
    private fun deleteMessage(messageId: String) {
        // If this message holds the current "Retry", TurnManager cancels the retry; otherwise it would resend the
        // text of a turn whose user row has just disappeared from the history.
        currentConversationId()?.let { turnManager.invalidateRetryFor(it, messageId) }
        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
        }
    }

    /**
     * The scope of chat search: while it is active the feed window limit is lifted temporarily so the
     * search sees the WHOLE history, not only the loaded "tail" (otherwise matches in old messages not yet
     * in the pagination window would not be found — it would look like "search does not work"). On leaving
     * the search the normal window returns — there is no reason to keep the whole history in memory all the
     * time.
     */
    fun setSearchActive(active: Boolean) {
        windowLimit.value = if (active) Int.MAX_VALUE else INITIAL_WINDOW
        if (!active) searchQueryFlow.value = ""
    }

    /** The current search text from the chat header — the background scan recomputes the matches (see init). */
    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    /**
     * The subscription to the window of the last messages. [windowLimit] controls the window size: on "load
     * older" it grows and flatMapLatest resubscribes to the larger query. hasMoreOlder is derived by the
     * heuristic "exactly the limit came back → there are probably older ones".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMessages(id: String) {
        messagesJob?.cancel()
        windowLimit.value = INITIAL_WINDOW
        olderLoadInFlight = false
        messagesJob = viewModelScope.launch {
            windowLimit
                .flatMapLatest { limit ->
                    conversationRepository.getMessagesWindow(id, limit).map { msgs -> msgs to limit }
                }
                .collect { (messages, limit) ->
                    olderLoadInFlight = false
                    // Removing the stream bubble when a message arrives is arbitrated in observeSession: there messages take
                    // part in the combine with the session status (isLoading holds until the Completed message
                    // appears in the feed window) — here it is only the feed itself.
                    _state.update {
                        it.copy(messages = messages, hasMoreOlder = messages.size >= limit)
                    }
                }
        }
    }

    /** A request to show older messages — the window grows. Guarded against repeated calls in flight. */
    fun loadOlderMessages() {
        if (olderLoadInFlight || !_state.value.hasMoreOlder) return
        olderLoadInFlight = true
        windowLimit.value += WINDOW_PAGE
    }

    private fun cancel() {
        currentConversationId()?.let { turnManager.cancel(it) }
        _state.update { it.copy(isLoading = false) }
    }

    companion object {
        /** The onboarding lesson (setup steps language/persona/profile) is driven by the onboarding screen, not the chat controller. */
        private const val ONBOARDING_LESSON_ID = "l1_welcome"

        /** The initial feed window size and the step for loading "older" messages. */
        private const val INITIAL_WINDOW = 40
        private const val WINDOW_PAGE = 40

        /** Debounce of the search input before scanning the history. */
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** The message for a PLAN turn after "Ask for changes" with an empty feedback field. */
        internal const val DEFAULT_PLAN_REVISION_REQUEST = "Пересобери план с учётом правок"
    }
}

private fun appendTranscript(current: String, transcript: String): String {
    if (current.isBlank()) return transcript
    return if (current.endsWith(' ') || current.endsWith('\n')) current + transcript
    else "$current $transcript"
}

private fun VoiceInputState.ErrorReason.toUserMessage(): String = when (this) {
    VoiceInputState.ErrorReason.Unavailable -> tr("Распознавание речи недоступно на устройстве", "Speech recognition isn't available on this device")
    VoiceInputState.ErrorReason.NoMatch -> tr("Не удалось распознать речь", "Couldn't recognize speech")
    VoiceInputState.ErrorReason.Network -> tr("Нет сети для распознавания речи", "No network for speech recognition")
    VoiceInputState.ErrorReason.Audio -> tr("Ошибка микрофона", "Microphone error")
    VoiceInputState.ErrorReason.Permission -> tr("Нет разрешения на доступ к микрофону", "No permission to use the microphone")
    VoiceInputState.ErrorReason.Busy -> tr("Распознаватель занят, попробуй ещё раз", "The recognizer is busy, try again")
    VoiceInputState.ErrorReason.Other -> tr("Не удалось распознать речь", "Couldn't recognize speech")
}
