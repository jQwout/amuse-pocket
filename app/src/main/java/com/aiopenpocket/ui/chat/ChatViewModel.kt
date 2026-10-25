package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonChatController
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.lesson.aboutMeFrom
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

    // Текст ввода — ОТДЕЛЬНЫЙ поток (не поле _state), по тому же принципу, что и streamingText ниже:
    // каждое нажатие клавиши иначе меняло бы идентичность state и рекомпозировало ВСЁ тело чата
    // (лента/оверлеи), а не только композер. Читается узким скоупом у поля ввода в ChatScreen.
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /** Текущий conversationId для подписок на сессию хода (пустой = чат ещё не создан). */
    private val convIdFlow = MutableStateFlow("")
    private var sessionJob: Job? = null

    // Стриминговый текст берём напрямую из сессии хода в TurnManager: ход (и его стрим) живёт в
    // appScope, а этот поток лишь переключается на сессию текущего диалога — вернулся на экран,
    // стрим продолжает бежать. Отдельный поток (не поле _state), чтобы частые дельты токенов
    // рекомпозировали только пузырь стрима, а не весь экран.
    @OptIn(ExperimentalCoroutinesApi::class)
    val streamingText: StateFlow<String> = convIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else turnManager.session(id) }
        .flatMapLatest { session -> session?.streamingText ?: flowOf("") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Пагинация ленты: показываем последние [windowLimit] сообщений, «старые» догружаем увеличением
    // лимита. Поток сообщений пересобирается через flatMapLatest при каждом изменении лимита.
    private val windowLimit = MutableStateFlow(INITIAL_WINDOW)
    private var olderLoadInFlight = false

    /** Поисковый запрос из шапки чата; скан совпадений — с дебаунсом и вне Main (см. init). */
    private val searchQueryFlow = MutableStateFlow("")

    private var messagesJob: Job? = null

    // Активный ход, ретрай-контекст и стрим-хэндофф переехали в TurnSession/TurnManager (appScope):
    // ход больше не умирает с экраном, а «Повторить» переживает пересоздание ViewModel.

    /** Защита от повторного входа при шаге урока (быстрый двойной тап «Дальше»/квиза). */
    private var lessonBusy = false

    init {
        // Лимит витков гоал-мода из настроек — держим в состоянии, чтобы передавать в оркестратор.
        viewModelScope.launch {
            userPreferencesRepository.goalModeMaxIterations.collect { n ->
                _state.update { it.copy(goalMaxIterations = n) }
            }
        }
        // Размер шрифта чата из настроек — применяется к тексту пузырей; меняется на лету.
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
        // Tool-approval бар (Task 9): coordinator публикует pending-запросы ГЛОБАЛЬНО (по всем
        // диалогам сразу, т.к. TurnManager гоняет ходы параллельно) — фильтруем на СВОЙ conversationId,
        // чтобы approval другого чата никогда не всплыл на этом экране. combine (не просто .collect
        // на pending) — потому что conversationId тоже меняется (setConversationId/ensureConversation).
        viewModelScope.launch {
            combine(convIdFlow, toolApprovalCoordinator.pending) { convId, pending ->
                pending.firstOrNull { it.conversationId == convId }
            }.collect { request ->
                _state.update { it.copy(pendingToolApproval = request) }
            }
        }
        // Task 10 (structured-plan-engine-907066): approval-бар плана — реактивно из PlanRepository,
        // не из событий хода (см. observePendingPlanApproval kdoc).
        observePendingPlanApproval()
        // Task 12: живой прогресс-бар плана в чате — та же реактивная подписка, но для
        // ACTIVE/PAUSED вместо AWAITING_APPROVAL (см. observeActivePlan kdoc).
        observeActivePlan()
        // Поиск по чату: дебаунс ввода (collectLatest + delay — новый символ отменяет хвост) и
        // contains-скан на Default. Раньше скан шёл прямо в композиции на каждый введённый символ —
        // на длинной истории это десятки мс на Main и пропуски кадров. contains(ignoreCase) в
        // Kotlin корректно фолдит кириллицу (SQL LIKE — нет), поэтому ищем в памяти, не в SQL.
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
     * the plan is approved — исполнение стартует сразу, в фоне (см. [runPlanCommand]) — or GOAL
     * advances it (a case [pendingPlanApproval] never covers, since it
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
        // Ход НЕ отменяем — он живёт в TurnManager (appScope) и дорабатывает в фоне; сбрасываем
        // только подписки экрана и экранное состояние.
        messagesJob?.cancel()
        sessionJob?.cancel()
        lessonBusy = false
        // Переносим pref-производные поля (шрифт/лимит витков) в новое состояние — иначе resetFor
        // сбросил бы их в дефолт, а реактивный коллектор из DataStore на неизменном значении не
        // переэмитит, и настройка застряла бы на дефолте.
        _state.update { prev -> ChatUiState.resetFor(id, prev.chatFontScale, prev.goalMaxIterations) }
        _inputText.value = "" // черновик ввода между диалогами не переносим (раньше это делал resetFor)
        convIdFlow.value = id
        // Новый чат (id пуст): диалога ещё нет, его создаст ensureConversation при отправке.
        // Коллектор по getMessages("") всегда пуст — не запускаем его.
        if (id.isBlank()) return
        observeMessages(id)
        observeSession(id)
        // Вход в диалог гасит бейдж «готово» в списке (Running/ошибку markSeen не трогает).
        turnManager.markSeen(id)
        // Урок-диалог: включаем интерактивные карточки. Если для заголовка есть JSON-урок — переходим в
        // lesson-режим и драйвим шаги контроллером (строгий контракт), иначе свободный путь (l2–l4).
        // Режим агента (план/гоал) НЕ включаем автоматически — только явный выбор в UI.
        viewModelScope.launch {
            val conversation = conversationRepository.getConversation(id)
            val tutorial = conversation?.isTutorial == true
            val title = conversation?.title
            // Резолвим полный снапшот (не только имя) один раз здесь — ChatScreen читает state.agent
            // напрямую вместо повторного обращения к MiniAgentCatalog/AgentResolver в композиции.
            val agent = conversation?.agentId?.let { agentId -> agentResolver.resolve(agentId) }
            // l1_welcome исключаем: его setup-шаги (language/persona/profile) контроллер не умеет —
            // онбординг идёт через свой экран, не через чат-драйвер.
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
                    // У урока заголовок диалога — его id (по нему урок и находится), а в шапке нужно
                    // человеческое название: иначе там висело бы «tokens_and_speed».
                    title = agent?.name ?: lesson?.title?.takeIf { it.isNotBlank() } ?: title,
                    lessonMode = lesson != null,
                    lesson = lesson,
                    // Режим по умолчанию из конфига агента. До этого поле конструктора не читал вообще
                    // никто, кроме тестового прогона в самом визарде: чат всегда открывался в CHAT,
                    // и «агент только для GOAL» вёл себя как обычный чат. Ставим один раз на открытие
                    // диалога (resetFor выше уже сбросил состояние), дальше решает выбор пользователя.
                    agentMode = agent?.defaultMode ?: it.agentMode,
                )
            }
            if (lesson != null) startOrResumeLesson(id, lesson)
        }
    }

    /**
     * Стартует урок (публикует шаг 0) или восстанавливает позицию после переоткрытия.
     * Курсор — явное состояние диалога (lessonStepIndex), а не счёт assistant-сообщений: так свободный
     * вопрос к LLM во время урока не сдвигает шаг (и удаление сообщения больше не десинхронит урок).
     */
    private suspend fun startOrResumeLesson(id: String, lesson: com.aiopenpocket.agent.core.lesson.Lesson) {
        // Курсор берём из ЯВНОГО состояния диалога (lessonStepIndex), а не из числа assistant-сообщений:
        // иначе свободный вопрос к LLM во время урока (лишний ответ) сдвигал бы шаг. См. LessonCursorPersistenceTest.
        val conversation = conversationRepository.getConversation(id)
        val persistedIndex = conversation?.lessonStepIndex ?: 0
        val persistedFinished = conversation?.lessonFinished ?: false
        if (!persistedFinished) {
            // Отдельной корутиной: картинки Muse Image рисуются десятки секунд, а первый шаг урока
            // ждать их не должен. Пройденные шаги не рисуем — их карточки уже в истории.
            viewModelScope.launch { lessonChatController.prefetchImages(lesson, fromIndex = persistedIndex) }
        }

        if (lesson.steps.isEmpty()) {
            // Урок без шагов: карточка завершения, чтобы экран не завис пустым — но без дублей при переоткрытии.
            if (!persistedFinished) {
                lessonChatController.postCompletion(id, lesson)
                conversationRepository.updateLessonCursor(id, stepIndex = 0, finished = true)
            }
            _state.update { it.copy(lessonStepIndex = 0, lessonFinished = true) }
            return
        }
        if (persistedIndex == 0 && !persistedFinished) {
            // Урок ещё не начат: публикуем первый шаг и фиксируем курсор = 1. Self-heal: если шаг 0 уже
            // в истории (краш между постом и записью курсора), не постим повторно — только чиним курсор,
            // чтобы не появился дубль первого шага.
            val alreadyPosted = conversationRepository.getAllMessages(id).any { it.role == "assistant" }
            if (!alreadyPosted) lessonChatController.postStep(id, lesson, 0)
            conversationRepository.updateLessonCursor(id, stepIndex = 1, finished = false)
            _state.update { it.copy(lessonStepIndex = 1, lessonFinished = false) }
        } else {
            // Возобновление: шаги уже в истории, просто восстанавливаем сохранённый курсор.
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
            // Смена режима инвалидирует «Повторить» прошлого хода (иначе ретрай уйдёт в старый режим).
            // pendingPlanApproval НЕ трогаем: это состояние плана в PlanRepository, а не режима чата.
            // Раньше чип обнулял его, а производный поток (distinctUntilChanged) заново его не
            // присылал — единственная карточка одобрения пропадала до перезахода в диалог.
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
            // «Поправить» — чисто локальный жест (см. ChatIntent.EditPlan kdoc): прячет бар, PlanRepository не трогает.
            is ChatIntent.EditPlan -> _state.update { it.copy(pendingPlanApproval = null) }
            is ChatIntent.AdvanceLesson -> advanceLesson()
            is ChatIntent.CompleteLessonQuiz -> completeLessonQuiz()
            is ChatIntent.PersonalizeLessonImage -> personalizeLessonImage(intent.imagePrompt, intent.caption)
            is ChatIntent.AnalyzeLessonVideo -> analyzeLessonVideo(intent.videoPrompt, intent.video)
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
                // Маршрут "chat/new" даёт пустой id — создаём диалог ДО launchTurn: сессия ключуется по id.
                val convId = ensureConversation(text)
                // Task 10 (structured-plan-engine-907066): planId=null — StructuredPlanOrchestrator сам
                // резолвит план диалога (PLAN предложит/пересмотрит текущий; GOAL продолжит ACTIVE или
                // предложит новый, если approved плана ещё нет). goalMaxIterations из настроек мапится на
                // maxLlmIterationsPerStep — тот же «бюджет рассуждения», просто теперь на ОДИН шаг плана.
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
     * Task 10 (structured-plan-engine-907066): maps the user's "лимит витков гоал-мода" setting
     * (`ChatUiState.goalMaxIterations`, from [com.aiopenpocket.agent.core.identity.UserPreferencesRepository])
     * onto [PlanRunBudget.maxLlmIterationsPerStep] — the closest surviving analogue now that a whole
     * GOAL turn is no longer one bounded ReAct loop but a run over one plan STEP at a time. The
     * Settings screen/repository for this value are unchanged; only where it lands downstream moved.
     */
    private fun goalRunBudget(maxIterations: Int) = PlanRunBudget(maxLlmIterationsPerStep = maxIterations)

    /**
     * Task 5 (structured-plan-engine-907066): typed plan commands go straight to [PlanCommands] —
     * no magic chat message. Одобрение ЗАПУСКАЕТ исполнение: [PlanCommands.approve] вызывает
     * `PlanScheduleSync.sync`, а тот сразу ставит heartbeat на первый шаг без расписания — шаги идут
     * в фоне (HeartbeatRunner), их ответы пишутся в этот же чат. GOAL-ход по-прежнему может продвинуть
     * план и вручную. «Попросить правки» ([requestPlanChanges]) — единственная команда, которая
     * следом сама заводит ход (PLAN), иначе новая версия плана не появлялась бы никогда. [runPlanCommand] mirrors
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
                // e.message здесь — проверки движка вроде «Cannot apply Pause to plan …» по-английски;
                // человеку нужна одна понятная строка, подробности — в логе.
                _state.update { it.copy(error = "Не удалось выполнить действие с планом — попробуй ещё раз.") }
            }
        }
    }

    private fun approvePlanCommand(planId: String, version: Int) = runPlanCommand {
        planCommands.approve(planId, version)
        _state.update { it.copy(pendingPlanApproval = null) }
    }

    /**
     * «Попросить правки»: план уходит в DRAFT, и сразу же — PLAN-ход в этом диалоге с текстом правок
     * (или с [DEFAULT_PLAN_REVISION_REQUEST], если поле пустое), чтобы планировщик предложил новую
     * версию. Раньше команда только переводила план в DRAFT: отзыв нигде не сохранялся
     * (PlanRepository.requestRevision его не хранит), карточка исчезала, и ничего не происходило.
     * Отзыв уходит репликой пользователя — так он и виден в чате, и попадает в промпт планировщика.
     */
    private fun requestPlanChanges(planId: String, version: Int, feedback: String) = runPlanCommand {
        planCommands.requestChanges(planId, version, feedback)
        _state.update { it.copy(pendingPlanApproval = null) }
        val convId = currentConversationId() ?: return@runPlanCommand
        val request = feedback.trim().ifEmpty { DEFAULT_PLAN_REVISION_REQUEST }
        turnManager.launchTurn(convId, request, AgentMode.PLAN)
    }

    /** «Дальше» по тексту и прохождение квиза одинаково продвигают курсор урока на следующий шаг. */
    private fun advanceLesson() = advanceLessonCursor()

    private fun completeLessonQuiz() = advanceLessonCursor()

    /**
     * Свободный вопрос к LLM во время урока: tool-less TUTOR-ход на том же диалоге ([AgentMode.TUTOR] —
     * только объясняет, без инструментов). Рамка репетитора — из systemPromptSuffix, а контекст текущего
     * шага инъектим через systemPromptExtra (карточные шаги пишутся с content="" и в историю НЕ попадают,
     * см. LessonQuestion + ревью-находку). Курсор урока персистится отдельно (lessonStepIndex), поэтому
     * лишний assistant-ответ его НЕ двигает (см. LessonCursorPersistenceTest).
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
                // TUTOR игнорирует planId/budget (диспатчится на AgentLoopOrchestrator, не на
                // StructuredPlanOrchestrator) — оставлены дефолтными.
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
     * «Нарисуй под меня» на шаге-картинке урока (см. LessonChatController.postPersonalizedImage).
     *
     * Сначала проверяем то, что пользователь может исправить сам, и называем это прямо: без «о себе»
     * рисовать не под кого. Иначе на всё был бы один ответ «не вышло».
     */
    private fun personalizeLessonImage(imagePrompt: String, caption: String) {
        val convId = currentConversationId() ?: return
        if (imagePrompt.isBlank() || imagePrompt in _state.value.personalizingImagePrompts) return
        _state.update {
            it.copy(personalizingImagePrompts = it.personalizingImagePrompts + imagePrompt, error = null, canRetry = false)
        }
        viewModelScope.launch {
            val problem = try {
                // Профиль из Room, язык из DataStore — всё на IO.
                val (aboutMe, language) = withContext(Dispatchers.IO) {
                    aboutMeFrom(memoryRepository.getFactsByCategory("profile")) to userPreferencesRepository.getLanguage()
                }
                when {
                    aboutMe.isBlank() -> "Чтобы нарисовать под тебя, расскажи о себе в профиле."
                    !lessonChatController.postPersonalizedImage(convId, imagePrompt, caption, aboutMe, language) ->
                        "Не получилось нарисовать. Проверь ключ OpenRouter и сеть и попробуй ещё раз."
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "personalize lesson image failed", e)
                "Не получилось нарисовать. Попробуй ещё раз."
            } finally {
                _state.update { it.copy(personalizingImagePrompts = it.personalizingImagePrompts - imagePrompt) }
            }
            if (problem != null) _state.update { it.copy(error = problem, canRetry = false) }
        }
    }

    /**
     * Шаг урока "video": ролик пользователя → Muse Spark → ответ в чат (LessonChatController.postVideoAnswer).
     *
     * Ролик читаем с потолком — длинное или 4K-видео не грузим в память целиком.
     * Снятый камерой файл (наш, в cacheDir) удаляем при любом исходе; выбранный из галереи — чужой, его
     * не трогаем.
     */
    private fun analyzeLessonVideo(videoPrompt: String, video: android.net.Uri) {
        val convId = currentConversationId() ?: return
        if (videoPrompt.isBlank() || videoPrompt in _state.value.analyzingVideoPrompts) return
        _state.update {
            it.copy(analyzingVideoPrompts = it.analyzingVideoPrompts + videoPrompt, error = null, canRetry = false)
        }
        viewModelScope.launch {
            val problem = try {
                val language = withContext(Dispatchers.IO) { userPreferencesRepository.getLanguage() }
                val clip = withContext(Dispatchers.IO) { readLessonVideo(video) }
                when {
                    clip == null -> "Видео слишком большое или не открылось — сними покороче, до 10 секунд."
                    !lessonChatController.postVideoAnswer(convId, videoPrompt, clip.first, clip.second, language) ->
                        "Не получилось разобрать видео. Проверь ключ OpenRouter и сеть и попробуй ещё раз."
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "lesson video failed", e)
                "Не получилось разобрать видео. Попробуй ещё раз."
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { deleteOwnCapture(video) }
                _state.update { it.copy(analyzingVideoPrompts = it.analyzingVideoPrompts - videoPrompt) }
            }
            if (problem != null) _state.update { it.copy(error = problem, canRetry = false) }
        }
    }

    /** Байты ролика и его тип, или null: не открылся или длиннее [VideoUnderstandingClient.MAX_VIDEO_BYTES]. */
    private fun readLessonVideo(video: android.net.Uri): Pair<ByteArray, String>? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(video)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val limit = com.aiopenpocket.agent.core.llm.VideoUnderstandingClient.MAX_VIDEO_BYTES
        // Ролик-пример урока лежит в assets APK: content resolver file:///android_asset не открывает.
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

    /** Удаляет ролик, снятый камерой для урока: он наш и лежит в cacheDir. Ролики из галереи не трогаем. */
    private fun deleteOwnCapture(video: android.net.Uri) {
        if (video.authority != "${context.packageName}.fileprovider") return
        val name = video.lastPathSegment ?: return
        java.io.File(java.io.File(context.cacheDir, com.aiopenpocket.ui.chat.components.LESSON_VIDEO_DIR), name).delete()
    }

    /**
     * Публикует следующий шаг урока, либо карточку завершения, если шагов больше нет. За счёт этого квиз
     * работает корректно, даже когда он НЕ последний шаг (после ответа просто идём дальше, а не завершаем).
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
                    _state.update { it.copy(lessonStepIndex = newIndex) }
                } else {
                    lessonChatController.postCompletion(convId, lesson)
                    conversationRepository.updateLessonCursor(convId, stepIndex = s.lessonStepIndex, finished = true)
                    _state.update { it.copy(lessonFinished = true) }
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
     * Проекция сессии хода из TurnManager в состояние экрана. Ход живёт в appScope — экран лишь
     * отражает его. «Мигание» конца хода решается деривацией: пузырь (isLoading) держим, пока
     * Completed-сообщение не приехало в окно ленты, — подмена атомарна, т.к. messages и статус
     * сходятся в одном combine и одном _state.update.
     */
    private fun observeSession(convId: String) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            turnManager.session(convId).collectLatest { session ->
                if (session == null) {
                    // Сессии нет (не было хода / отменён / просмотрен) — только гасим индикацию,
                    // остальное состояние (сообщения, статистика) не трогаем.
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
                                // Ответ доехал до ленты — чистая сессия больше не нужна (гасит бейдж
                                // «готово» в списке; markSeen сам не тронет ошибку/canRetry).
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
        // Персона из Настроек, а не дефолт репозитория: этим путём приходят чаты, начатые голосовым
        // ассистентом (маршрут chat/new), и без неё они всегда говорили бы «Резким напарником».
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

    /** Живая подписка карточки reminder — см. [ReminderCardView]. */
    // @trace heartbeat-napominalki-agenta-5f0442
    fun observeHeartbeat(id: String): Flow<HeartbeatEntity?> = heartbeatDao.observe(id)

    /** Живая подписка [com.aiopenpocket.ui.plan.PlanCard] — см. его же kdoc "never trusts a field copied into a chat message". */
    // @trace structured-plan-engine-907066
    fun observePlan(planId: String): Flow<PlanSnapshot?> = planRepository.observe(planId)

    /** Отмена из карточки: гасим WorkManager-задачу и фиксируем статус в Room (карточка сама перерисуется). */
    // @trace heartbeat-napominalki-agenta-5f0442
    fun cancelHeartbeat(id: String) {
        viewModelScope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(HeartbeatTool.workName(id))
            heartbeatDao.setStatus(id, "cancelled")
        }
    }

    /** Удаляет сообщение. `state.messages` реактивен (Flow из БД), поэтому лента обновится сама. */
    private fun deleteMessage(messageId: String) {
        // Если это сообщение держит текущий «Повторить» — TurnManager погасит ретрай, иначе он
        // переотправит текст хода, чей user-ряд в истории только что исчез.
        currentConversationId()?.let { turnManager.invalidateRetryFor(it, messageId) }
        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
        }
    }

    /**
     * Область поиска по чату: пока активен — временно снимаем лимит окна ленты, чтобы поиск видел
     * ВСЮ историю, а не только подгруженный «хвост» (иначе совпадения в старых сообщениях, ещё не
     * попавших в окно пагинации, не находились бы — выглядело бы как «поиск не работает»). При
     * выходе из поиска возвращаем обычное окно — незачем держать всю историю в памяти постоянно.
     */
    fun setSearchActive(active: Boolean) {
        windowLimit.value = if (active) Int.MAX_VALUE else INITIAL_WINDOW
        if (!active) searchQueryFlow.value = ""
    }

    /** Текущий текст поиска из шапки чата — совпадения пересчитает фоновый скан (см. init). */
    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    /**
     * Подписка на окно последних сообщений. [windowLimit] управляет размером окна: при «подгрузке
     * старых» он растёт, flatMapLatest переподписывается на больший запрос. hasMoreOlder выводим
     * эвристикой «вернули ровно лимит → вероятно, есть ещё старше».
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
                    // Снятие стрим-пузыря при приходе сообщения арбитрируется в observeSession: там
                    // messages участвуют в combine со статусом сессии (isLoading держится, пока
                    // Completed-сообщение не появилось в окне ленты) — здесь только сама лента.
                    _state.update {
                        it.copy(messages = messages, hasMoreOlder = messages.size >= limit)
                    }
                }
        }
    }

    /** Просьба показать более старые сообщения — растим окно. Защита от повторных вызовов в полёте. */
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
        /** Урок-онбординг (setup-шаги language/persona/profile) драйвится не чат-контроллером, а экраном онбординга. */
        private const val ONBOARDING_LESSON_ID = "l1_welcome"

        /** Начальный размер окна ленты и шаг догрузки «старых» сообщений. */
        private const val INITIAL_WINDOW = 40
        private const val WINDOW_PAGE = 40

        /** Дебаунс поискового ввода перед сканом истории. */
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** Реплика для PLAN-хода после «Попросить правки» с пустым полем отзыва. */
        internal const val DEFAULT_PLAN_REVISION_REQUEST = "Пересобери план с учётом правок"
    }
}

private fun appendTranscript(current: String, transcript: String): String {
    if (current.isBlank()) return transcript
    return if (current.endsWith(' ') || current.endsWith('\n')) current + transcript
    else "$current $transcript"
}

private fun VoiceInputState.ErrorReason.toUserMessage(): String = when (this) {
    VoiceInputState.ErrorReason.Unavailable -> "Распознавание речи недоступно на устройстве"
    VoiceInputState.ErrorReason.NoMatch -> "Не удалось распознать речь"
    VoiceInputState.ErrorReason.Network -> "Нет сети для распознавания речи"
    VoiceInputState.ErrorReason.Audio -> "Ошибка микрофона"
    VoiceInputState.ErrorReason.Permission -> "Нет разрешения на доступ к микрофону"
    VoiceInputState.ErrorReason.Busy -> "Распознаватель занят, попробуй ещё раз"
    VoiceInputState.ErrorReason.Other -> "Не удалось распознать речь"
}
