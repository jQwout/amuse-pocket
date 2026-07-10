package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ходы агента, отвязанные от жизненного цикла экрана: appScope-синглтон держит по TurnSession на
 * диалог, ход дорабатывает в фоне после ухода с экрана чата. Внутри диалога ходы сериализуются
 * (новый делает cancelAndJoin предыдущего — как прежний launchTurn во ViewModel), между диалогами
 * идут параллельно. Экран — только проектор: подписался на session(convId), отписался — ходу всё равно.
 *
 * Граница живучести — процесс: смерть процесса/смахивание из recents ход не переживает (осознанный
 * трейдофф вместо foreground-сервиса, см. спеку 2026-07-04-background-turns-design.md).
 */
class TurnManager(
  private val executor: TurnExecutor,
  private val persistence: TurnPersistence,
  private val foreground: ForegroundState,
  private val notifier: TurnNotifier,
  private val scope: CoroutineScope,
  /** Монотонные часы для гейта стрима (SystemClock.uptimeMillis в проде, фейк в тестах). */
  private val monotonicNowMs: () -> Long,
  private val log: AgentLog,
) {

  /** Guard мапы сессий и подмены job — launchTurn может прилететь с любого потока. */
  private val mutex = Mutex()

  private val sessionsFlow = MutableStateFlow<Map<String, TurnSession>>(emptyMap())

  /** Активные и завершённые-непросмотренные сессии по conversationId (для бейджей списка). */
  val sessions: StateFlow<Map<String, TurnSession>> = sessionsFlow.asStateFlow()

  fun session(conversationId: String): Flow<TurnSession?> =
    sessionsFlow.map { it[conversationId] }.distinctUntilChanged()

  /** Запускает ход: пишет реплику пользователя в историю и гонит агентный цикл до конца. */
  fun launchTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ) {
    scope.launch { startTurn(conversationId, userText, mode, planApproved, goalMaxIterations, persistUserMessage = true) }
  }

  /** Останавливает ход и убирает сессию: для UI диалог возвращается в Idle, без ошибки. */
  fun cancel(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        session.job?.cancel()
        sessionsFlow.update { it - conversationId }
      }
    }
  }

  /** Повтор последнего хода после ошибки: реплика пользователя уже в истории, addMessage не повторяем. */
  fun retry(conversationId: String) {
    scope.launch {
      val session = sessionsFlow.value[conversationId] ?: return@launch
      val text = session.lastUserText ?: return@launch
      startTurn(conversationId, text, session.lastMode, session.lastPlanApproved, session.lastGoalMaxIterations, persistUserMessage = false)
    }
  }

  /** План-мод: пользователь одобрил план → фаза выполнения (инструменты включены). */
  fun approvePlan(conversationId: String) {
    scope.launch {
      val goalIterations = sessionsFlow.value[conversationId]?.lastGoalMaxIterations ?: 3
      startTurn(conversationId, TurnTexts.APPROVE_PLAN_MESSAGE, AgentMode.PLAN, planApproved = true, goalMaxIterations = goalIterations, persistUserMessage = true)
    }
  }

  /** «Поправить» вместо «Выполнить»: бар одобрения прячем, сессию и ретрай не трогаем. */
  fun dismissPlanApproval(conversationId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    session.statusFlow.update { status ->
      if (status is TurnStatus.Completed) status.copy(planAwaitingApproval = false) else status
    }
  }

  /** Смена режима агента инвалидирует «Повторить» прошлого хода (иначе ретрай уйдёт в старый режим). */
  fun invalidateRetry(conversationId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    session.lastUserText = null
    session.lastUserMessageId = null
    session.statusFlow.update { status ->
      when (status) {
        is TurnStatus.Failed -> status.copy(canRetry = false)
        is TurnStatus.Completed -> status.copy(canRetry = false)
        else -> status
      }
    }
  }

  /** Удалили сообщение, которое держит «Повторить», — гасим ретрай (см. deleteMessage до переезда). */
  fun invalidateRetryFor(conversationId: String, messageId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    if (session.lastUserMessageId == messageId) invalidateRetry(conversationId)
  }

  /** Интент ClearError: пользователь закрыл баннер ошибки — сессия с ошибкой больше не нужна. */
  fun clearError(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        val status = session.status.value
        val hasError = status is TurnStatus.Failed || (status is TurnStatus.Completed && status.error != null)
        if (hasError) {
          session.job?.cancel()
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /**
   * Ход просмотрен (вход в диалог, либо ответ доехал до ленты) — чисто завершённую сессию убираем,
   * чтобы бейдж «готово» в списке погас. Running/Failed/план-бар/blank-error не трогаем: они ещё
   * нужны экрану (индикация, ретрай, одобрение).
   */
  fun markSeen(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        val status = session.status.value
        val cleanlyDone = status is TurnStatus.Completed &&
          status.error == null && !status.planAwaitingApproval && !status.canRetry
        if (cleanlyDone) {
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /** Статусы ходов по диалогам — бейджи списка диалогов («варится» / «готово» / «ошибка»). */
  @OptIn(ExperimentalCoroutinesApi::class)
  val badges: Flow<Map<String, TurnStatus>> = sessionsFlow.flatMapLatest { map ->
    if (map.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(map.map { (id, session) -> session.status.map { id to it } }) { pairs -> pairs.toMap() }
    }
  }

  /**
   * Единая точка старта (отправка/ретрай/одобрение плана). Прошлый ход этого же диалога мог ещё
   * выполнять инструмент или запись — гасим его и ждём внутри нового job, прежде чем стартовать
   * (перенос семантики прежнего ChatViewModel.launchTurn, но с ключом-диалогом вместо одного
   * глобального activeJob).
   */
  internal suspend fun startTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
    persistUserMessage: Boolean,
  ) {
    mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: TurnSession(conversationId, monotonicNowMs).also { created ->
        sessionsFlow.update { it + (conversationId to created) }
      }
      // Гасим прошлый ход СРАЗУ под мьютексом, а join ждём уже внутри нового job. Иначе, если новый
      // job отменят (Stop/clearError) раньше, чем он дойдёт до cancelAndJoin, прошлый ход остался бы
      // недоотменённым и продолжил бы писать ответ в «остановленный» диалог.
      val previous = session.job
      previous?.cancel()
      session.job = scope.launch {
        previous?.join()
        runTurn(session, userText, mode, planApproved, goalMaxIterations, persistUserMessage)
      }
    }
  }

  private suspend fun runTurn(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
    persistUserMessage: Boolean,
  ) {
    val convId = session.conversationId
    resetStreaming(session)
    session.toolTraceFlow.value = emptyList()
    session.responseStartTime = System.currentTimeMillis()
    session.statusFlow.value = TurnStatus.Running
    session.lastMode = mode
    session.lastPlanApproved = planApproved
    session.lastGoalMaxIterations = goalMaxIterations
    try {
      if (persistUserMessage) {
        // Новый ход — новая попытка: ретрай-контекст валиден только после успешного addMessage.
        session.lastUserText = null
        session.lastUserMessageId = null
        val userMsgId = persistence.addUserMessage(convId, userText)
        session.lastUserText = userText
        session.lastUserMessageId = userMsgId
      }
      collectTurnEvents(session, userText, mode, planApproved, goalMaxIterations)
    } catch (e: CancellationException) {
      // Stop / новый ход: отмена — штатный путь.
      throw e
    } catch (e: Exception) {
      log.e(TAG, "Agent turn error", e)
      finishFailed(session, e.message ?: "Unknown error")
    }
  }

  /** Проекция событий агентного цикла в сессию — прямой перенос веток из ChatViewModel.runAgent. */
  private suspend fun collectTurnEvents(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ) {
    // Временно гоним всё на NIM; выбор провайдера (GROQ/GITHUB) выпилим позже.
    val flow = executor.execute(session.conversationId, userText, LlmProvider.NIM, mode, planApproved, goalMaxIterations)
    flow.collect { event ->
      when (event) {
        is AgentEvent.TextDelta ->
          // Гейт частоты (~20 пуб/сек) — в StreamingTextBuffer; null = дельта скоплена молча.
          session.streamBuffer.append(event.content)?.let { session.streamingTextFlow.value = it }

        is AgentEvent.ToolCompleted -> {
          val newTrace = ToolTrace(
            iteration = event.iteration,
            toolName = event.name,
            arguments = event.arguments,
            result = event.result,
          )
          session.toolTraceFlow.update { it + newTrace }
        }

        is AgentEvent.Completed -> handleCompleted(session, mode, planApproved, event.result)

        is AgentEvent.Error -> finishFailed(session, event.message)

        is AgentEvent.StateChanged -> when (event.state) {
          // Новый виток ReAct стримит свой текст заново — показываем текст ТЕКУЩЕГО витка.
          AgentState.STREAMING -> resetStreaming(session)
          // Стрим витка закончился: дофлашиваем хвост, не прошедший гейт частоты.
          else -> session.streamBuffer.flush()?.let { session.streamingTextFlow.value = it }
        }

        else -> { /* no-op */
        }
      }
    }
  }

  private suspend fun handleCompleted(
    session: TurnSession,
    mode: AgentMode,
    planApproved: Boolean,
    result: AgentResult,
  ) {
    val convId = session.conversationId
    // Все решения (NEED_INFO, blank-ошибки, GOAL-фолбэк, карточки) — в чистой политике; здесь
    // только применяем: записать, обновить статус, уведомить.
    val outcome = resolveCompletedTurn(mode, planApproved, result, hasRetryContext = session.lastUserText != null)
    val insertedId = if (outcome.responseText.isNotBlank() || outcome.cardSpecs.isNotEmpty()) {
      persistence.addAssistantMessage(convId, outcome.responseText, outcome.cardSpecs.firstOrNull())
    } else {
      null
    }
    outcome.cardSpecs.drop(1).forEach { spec ->
      persistence.addAssistantMessage(convId, "", spec)
    }

    session.toolTraceFlow.value = result.toolTrace
    // streamingText НЕ сбрасываем: экран держит пузырь, пока сообщение не приедет в окно ленты
    // (замена pendingHandoffId — «мигание» конца хода не возвращается).
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = result.completionTokensEstimate,
      contextTokensUsed = result.promptTokensEstimate,
      error = outcome.blankError,
      canRetry = outcome.canRetry,
      planAwaitingApproval = outcome.planAwaitingApproval,
    )
    notifyIfBackground(convId, preview = outcome.responseText.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
  }

  private suspend fun finishFailed(session: TurnSession, message: String) {
    session.statusFlow.value = TurnStatus.Failed(message, canRetry = session.lastUserText != null)
    notifyIfBackground(session.conversationId, preview = message, failed = true)
  }

  /** Пуш — только когда приложение реально в фоне; внутри приложения — бейджи списка диалогов. */
  private suspend fun notifyIfBackground(conversationId: String, preview: String, failed: Boolean) {
    if (foreground.isInForeground()) return
    val title = runCatching { persistence.conversationTitle(conversationId) }.getOrNull()
    notifier.turnFinished(conversationId, title, preview, failed)
  }

  private fun resetStreaming(session: TurnSession) {
    session.streamBuffer.reset()
    session.streamingTextFlow.value = ""
  }

  companion object {
    private const val TAG = "TurnManager"
  }
}
