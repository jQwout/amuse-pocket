package com.aiopenpocket.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonChatController
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.PlanExecuteOrchestrator
import com.aiopenpocket.agent.loop.SupervisorOrchestrator
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.voice.input.VoiceInputController
import com.aiopenpocket.voice.input.VoiceInputState
import com.aiopenpocket.voice.input.appLanguageToBcp47
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ChatViewModel::class)
class ChatViewModel(
  private val conversationRepository: ConversationRepository,
  private val agentLoop: AgentLoopOrchestrator,
  // Plan-and-Execute остаётся в коде, но больше не на кнопке план-мода (её заменил agentMode-гейт).
  private val planExecuteOrchestrator: PlanExecuteOrchestrator,
  private val supervisorOrchestrator: SupervisorOrchestrator,
  private val settingsRepository: SettingsRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val lessonRepository: LessonRepository,
  private val lessonChatController: LessonChatController,
  private val voiceController: VoiceInputController,
) : ViewModel() {

  private val _state = MutableStateFlow(ChatUiState())
  val state: StateFlow<ChatUiState> = _state.asStateFlow()

  private var activeJob: Job? = null
  private var messagesJob: Job? = null
  /** Last user message of the active turn, kept so a failed turn can be retried without re-adding it. */
  private var lastUserText: String? = null
  /** Режим/фаза последнего хода — чтобы ретрай повторял то же самое (план/гоал/чат). */
  private var lastMode: AgentMode = AgentMode.CHAT
  private var lastPlanApproved: Boolean = false
  /** Защита от повторного входа при шаге урока (быстрый двойной тап «Дальше»/квиза). */
  private var lessonBusy = false
  private var responseStartTime: Long = 0L

  init {
    // Лимит витков гоал-мода из настроек — держим в состоянии, чтобы передавать в оркестратор.
    viewModelScope.launch {
      userPreferencesRepository.goalModeMaxIterations.collect { n ->
        _state.update { it.copy(goalMaxIterations = n) }
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
        _state.update { it.copy(inputText = appendTranscript(it.inputText, transcript)) }
      }
    }
  }

  override fun onCleared() {
    voiceController.release()
    super.onCleared()
  }

  fun setConversationId(id: String) {
    if (_state.value.conversationId == id) return
    activeJob?.cancel()
    messagesJob?.cancel()
    lastUserText = null
    lastMode = AgentMode.CHAT
    lastPlanApproved = false
    lessonBusy = false
    _state.value = ChatUiState.resetFor(id)
    // Новый чат (id пуст): диалога ещё нет, его создаст ensureConversation при отправке.
    // Коллектор по getMessages("") всегда пуст — не запускаем его.
    if (id.isBlank()) return
    observeMessages(id)
    // Урок-диалог: включаем интерактивные карточки. Если для заголовка есть JSON-урок — переходим в
    // lesson-режим и драйвим шаги контроллером (строгий контракт), иначе свободный путь (l2–l4).
    // Режим агента (план/гоал) НЕ включаем автоматически — только явный выбор в UI.
    viewModelScope.launch {
      val conversation = conversationRepository.getConversation(id)
      val tutorial = conversation?.isTutorial == true
      val title = conversation?.title
      val agentName = conversation?.agentId?.let { agentId -> MiniAgentCatalog.get(agentId)?.name }
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
          title = agentName ?: title,
          lessonMode = lesson != null,
          lesson = lesson,
        )
      }
      if (lesson != null) startOrResumeLesson(id, lesson)
    }
  }

  /**
   * Стартует урок (публикует шаг 0) или восстанавливает позицию после переоткрытия.
   * Контракт «1 шаг = 1 assistant-сообщение» позволяет восстановить курсор простым счётом сообщений.
   */
  private suspend fun startOrResumeLesson(id: String, lesson: com.aiopenpocket.agent.core.lesson.Lesson) {
    if (lesson.steps.isEmpty()) {
      // Урок без шагов: карточка завершения, чтобы экран не завис пустым — но без дублей при переоткрытии.
      val alreadyPosted = conversationRepository.getAllMessages(id).any { it.role == "assistant" }
      if (!alreadyPosted) lessonChatController.postCompletion(id, lesson)
      _state.update { it.copy(lessonStepIndex = 0, lessonFinished = true) }
      return
    }
    val posted = conversationRepository.getAllMessages(id).count { it.role == "assistant" }
    if (posted == 0) {
      lessonChatController.postStep(id, lesson, 0)
      _state.update { it.copy(lessonStepIndex = 1, lessonFinished = false) }
    } else {
      _state.update {
        it.copy(
          lessonStepIndex = posted.coerceAtMost(lesson.steps.size),
          lessonFinished = posted > lesson.steps.size,
        )
      }
    }
  }

  /** Sends [text] into the conversation as a user message (used by interactive cards). */
  fun sendUserMessage(text: String) {
    if (text.isBlank()) return
    _state.update { it.copy(inputText = text) }
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
      is ChatIntent.UpdateInput -> _state.update { it.copy(inputText = intent.text) }
      is ChatIntent.SendMessage -> sendMessage()
      is ChatIntent.CancelRequest -> cancel()
      is ChatIntent.RetryRequest -> retry()
      is ChatIntent.ToggleToolTrace -> _state.update { it.copy(showToolTrace = !it.showToolTrace) }
      is ChatIntent.TogglePin -> togglePin(intent.messageId)
      is ChatIntent.ClearError -> _state.update { it.copy(error = null, canRetry = false) }
      is ChatIntent.NavigateBack -> { /* handled by navigation */ }
      is ChatIntent.ToggleVoiceInput -> toggleVoiceInput()
      is ChatIntent.ClearVoiceError -> {
        voiceController.clearError()
        _state.update { it.copy(voiceError = null) }
      }
      // Смена режима инвалидирует «Повторить» прошлого хода (иначе ретрай уйдёт в старый режим).
      is ChatIntent.SetMode -> _state.update { it.copy(agentMode = intent.mode, planAwaitingApproval = false, canRetry = false) }
      is ChatIntent.ApprovePlan -> approvePlan()
      is ChatIntent.EditPlan -> _state.update { it.copy(planAwaitingApproval = false) }
      is ChatIntent.AdvanceLesson -> advanceLesson()
      is ChatIntent.CompleteLessonQuiz -> completeLessonQuiz()
    }
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
    val text = _state.value.inputText.trim()
    if (text.isEmpty()) return

    // Новый ход — новая попытка. lastUserText выставим только после успешного addMessage.
    lastUserText = null
    // responseStartTime фиксирует начало ожидания, чтобы ResponseStatsBar показал «время ответа».
    responseStartTime = System.currentTimeMillis()
    launchTurn(resetInput = true) {
      // Маршрут "chat/new" даёт пустой id — создаём диалог, иначе FK-нарушение при addMessage.
      val convId = ensureConversation(text)
      // Сначала ждём сохранения сообщения пользователя в БД
      conversationRepository.addMessage(convId, "user", text)
      // Сообщение уже в истории — запоминаем ход для возможного ретрая (без повторного addMessage).
      lastUserText = text
      // В план-моде обычная отправка — это фаза ПЛАНИРОВАНИЯ (planApproved=false): план, без инструментов.
      runAgent(convId, text, _state.value.agentMode, planApproved = false)
    }
  }

  /** План-мод: пользователь одобрил план → запускаем фазу выполнения (инструменты включены). */
  private fun approvePlan() {
    val convId = _state.value.conversationId?.takeIf { it.isNotBlank() } ?: return
    if (!_state.value.planAwaitingApproval) return
    _state.update { it.copy(planAwaitingApproval = false) }
    val approveMsg = "Одобряю план, выполни его по шагам."
    launchTurn(resetInput = false) {
      conversationRepository.addMessage(convId, "user", approveMsg)
      lastUserText = approveMsg
      runAgent(convId, approveMsg, AgentMode.PLAN, planApproved = true)
    }
  }

  /** «Дальше» по тексту и прохождение квиза одинаково продвигают курсор урока на следующий шаг. */
  private fun advanceLesson() = advanceLessonCursor()

  private fun completeLessonQuiz() = advanceLessonCursor()

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
          _state.update { it.copy(lessonStepIndex = it.lessonStepIndex + 1) }
        } else {
          lessonChatController.postCompletion(convId, lesson)
          _state.update { it.copy(lessonFinished = true) }
        }
      } finally {
        lessonBusy = false
      }
    }
  }

  /**
   * Повторяет последний ход агента после ошибки. Сообщение пользователя уже сохранено в истории
   * (его записал sendMessage), поэтому заново его не добавляем — только перезапускаем агента.
   */
  private fun retry() {
    val convId = _state.value.conversationId?.takeIf { it.isNotBlank() } ?: return
    val text = lastUserText ?: return
    launchTurn(resetInput = false) {
      runAgent(convId, text, lastMode, lastPlanApproved)
    }
  }

  /**
   * Единый запуск хода агента (общий для отправки и ретрая). Дожидается завершения прошлого хода
   * через cancelAndJoin — два агентных цикла не пересекаются, поэтому инструменты и addMessage не
   * выполняются параллельно при быстром повторном тапе. [block] содержит специфичную для вызова
   * подготовку (создание диалога, сохранение сообщения) и сам runAgent; отмена и ошибки
   * обрабатываются здесь одинаково для обоих путей.
   */
  private fun launchTurn(resetInput: Boolean, block: suspend () -> Unit) {
    val previous = activeJob
    activeJob = viewModelScope.launch {
      // Прошлый ход мог ещё выполнять инструмент/запись — гасим его и ждём, прежде чем стартовать новый.
      previous?.cancelAndJoin()
      _state.update {
        it.copy(
          inputText = if (resetInput) "" else it.inputText,
          isLoading = true,
          streamingText = "",
          toolTrace = emptyList(),
          error = null,
          canRetry = false,
          // Любой новый ход прячет бар одобрения; для план-планирования он вернётся на завершении.
          planAwaitingApproval = false,
        )
      }
      try {
        block()
      } catch (e: kotlinx.coroutines.CancellationException) {
        // Юзер нажал Stop / начал новый ход: отмена — штатный путь, не показываем как ошибку.
        throw e
      } catch (e: Exception) {
        android.util.Log.e("ChatViewModel", "Agent turn error", e)
        _state.update { it.copy(isLoading = false, error = e.message, canRetry = lastUserText != null) }
      }
    }
  }

  /** Запускает агентный цикл и проецирует его события в состояние. Общий путь для отправки и ретрая. */
  private suspend fun runAgent(
    convId: String,
    userMessage: String,
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
  ) {
    lastMode = mode
    lastPlanApproved = planApproved
    // Временно гоним всё на NIM; выбор провайдера (GROQ/GITHUB) выпилим позже.
    val provider = LlmProvider.NIM
    // Маршрут хода: координатор-агент → супервизор (мульти-агент); иначе — обычный чат-цикл с режимом
    // агента (план-гейт / гоал-петля), скоупленный по привязанному мини-агенту.
    val flow = when {
      _state.value.agentId == MiniAgentCatalog.SUPERVISOR_ID ->
        supervisorOrchestrator.execute(convId, userMessage, provider)
      else ->
        agentLoop.execute(convId, userMessage, provider, mode, planApproved, _state.value.goalMaxIterations)
    }
    flow.collect { event ->
      when (event) {
        is AgentEvent.TextDelta -> {
          _state.update { it.copy(streamingText = it.streamingText + event.content) }
        }
        is AgentEvent.ToolCompleted -> {
          val newTrace = com.aiopenpocket.agent.loop.ToolTrace(
            iteration = event.iteration,
            toolName = event.name,
            arguments = event.arguments,
            result = event.result,
          )
          _state.update { it.copy(toolTrace = it.toolTrace + newTrace) }
        }
        is AgentEvent.Completed -> {
          // Extract render_card specs so the Driven UI cards render inline in chat.
          val cardSpecs = event.result.toolTrace
            .filter { it.toolName == "render_card" }
            .mapNotNull { (it.result as? ToolResult.Success)?.data }

          // Фаза планирования: маркер [[NEED_INFO]] В НАЧАЛЕ ответа = это уточняющий вопрос, не план.
          // Матчим именно префикс (а не contains), чтобы план, упомянувший литерал, не спрятал бар.
          val planningPhase = mode == AgentMode.PLAN && !planApproved
          val rawText = event.result.text
          val needInfo = planningPhase && rawText.trimStart().startsWith(NEED_INFO_MARKER)
          val responseText = when {
            needInfo -> rawText.trimStart().removePrefix(NEED_INFO_MARKER).trim()
              .ifBlank { "Уточни, пожалуйста, детали задачи — что именно нужно сделать?" }
            planningPhase -> rawText.trim()
            else -> rawText
          }
          if (responseText.isNotBlank() || cardSpecs.isNotEmpty()) {
            conversationRepository.addMessage(
              convId,
              "assistant",
              responseText,
              cardSpec = cardSpecs.firstOrNull(),
            )
          }
          // Any additional cards become their own card-only messages.
          cardSpecs.drop(1).forEach { spec ->
            conversationRepository.addMessage(convId, "assistant", "", cardSpec = spec)
          }
          // Бар одобрения — только когда выдан НЕПУСТОЙ настоящий план (не вопрос и не пустой ответ),
          // иначе «Выполнить» запустил бы инструменты против несуществующего плана.
          val planningDone = planningPhase && !needInfo && responseText.isNotBlank()
          // Пустой ответ в фазе планирования: не оставляем немой экран — даём понятный ретрай.
          val planningBlank = planningPhase && responseText.isBlank() && cardSpecs.isEmpty()
          val stats = ResponseStats(
            responseTimeMs = System.currentTimeMillis() - responseStartTime,
            completionTokens = event.result.completionTokensEstimate,
            contextTokensUsed = event.result.promptTokensEstimate,
          )
          _state.update {
            it.copy(
              isLoading = false,
              streamingText = "",
              toolTrace = event.result.toolTrace,
              error = if (planningBlank) "Модель не вернула план — попробуй ещё раз." else null,
              canRetry = if (planningBlank) lastUserText != null else false,
              planAwaitingApproval = planningDone,
              lastResponseStats = stats,
            )
          }
        }
        is AgentEvent.Error -> {
          _state.update { it.copy(isLoading = false, error = event.message, canRetry = lastUserText != null) }
        }
        else -> { /* no-op */ }
      }
    }
  }

  private suspend fun ensureConversation(firstMessage: String): String {
    _state.value.conversationId?.takeIf { it.isNotBlank() }?.let { return it }
    val title = ConversationRepository.placeholderTitle(firstMessage)
    val newId = conversationRepository.createConversation(title = title)
    _state.update { it.copy(conversationId = newId) }
    observeMessages(newId)
    return newId
  }

  private fun togglePin(messageId: String) {
    val current = _state.value.messages.find { it.id == messageId } ?: return
    viewModelScope.launch {
      conversationRepository.setMessagePinned(messageId, !current.isPinned)
    }
  }

  private fun observeMessages(id: String) {
    messagesJob?.cancel()
    messagesJob = viewModelScope.launch {
      conversationRepository.getMessages(id).collect { messages ->
        _state.update { it.copy(messages = messages) }
      }
    }
  }

  private fun cancel() {
    activeJob?.cancel()
    _state.update { it.copy(isLoading = false, streamingText = "") }
  }

  companion object {
    /** Маркер план-планирования: модель ставит его в начало ответа, когда вместо плана задаёт
     *  уточняющие вопросы. Из текста для пользователя вырезается, бар одобрения тогда не показываем. */
    private const val NEED_INFO_MARKER = "[[NEED_INFO]]"
    /** Урок-онбординг (setup-шаги language/persona/profile) драйвится не чат-контроллером, а экраном онбординга. */
    private const val ONBOARDING_LESSON_ID = "l1_welcome"
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
  VoiceInputState.ErrorReason.Busy -> "Распознаватель занят, попробуйте ещё раз"
  VoiceInputState.ErrorReason.Other -> "Не удалось распознать речь"
}
