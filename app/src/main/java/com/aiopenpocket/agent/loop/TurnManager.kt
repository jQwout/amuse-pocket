package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
  private val lifeSupport: TurnLifeSupport,
  private val scope: CoroutineScope,
  /** Монотонные часы для гейта стрима (SystemClock.uptimeMillis в проде, фейк в тестах). */
  private val monotonicNowMs: () -> Long,
  /**
   * Провайдер, выбранный пользователем в Настройках. Лямбда, а не значение — как [monotonicNowMs] и
   * `apiKeyResolver`: читается заново на КАЖДЫЙ ход, поэтому переключение подхватывается со
   * следующего же сообщения, без пересборки DI-графа.
   *
   * Без значения по умолчанию СОЗНАТЕЛЬНО (как toolApprovalCoordinator у ReactLoopRunner): `= {
   * LlmProvider.NIM }` означало бы, что потерянный в DI аргумент молча возвращает всё приложение к
   * NVIDIA и ничего при этом не падает.
   */
  private val selectedProvider: () -> LlmProvider,
  private val log: AgentLog,
) {

  /** Guard мапы сессий и подмены job — launchTurn может прилететь с любого потока. */
  private val mutex = Mutex()

  private val sessionsFlow = MutableStateFlow<Map<String, TurnSession>>(emptyMap())

  init {
    // Единственный подписчик: только менеджер владеет job-ами ходов и может их отменить.
    lifeSupport.onProtectionLost { protectedIds -> scope.launch { failProtectedTurns(protectedIds) } }
  }

  /** Активные и завершённые-непросмотренные сессии по conversationId (для бейджей списка). */
  val sessions: StateFlow<Map<String, TurnSession>> = sessionsFlow.asStateFlow()

  fun session(conversationId: String): Flow<TurnSession?> =
    sessionsFlow.map { it[conversationId] }.distinctUntilChanged()

  /**
   * Запускает ход: пишет реплику пользователя в историю и гонит агентный цикл до конца.
   * [executionMode] — LIVE (по умолчанию, обычный чат) или PREVIEW (builder-тест, Task 20) — не
   * заводит параллельный вход: едет тем же трактом, что и обычная отправка.
   *
   * [planId]/[budget] (Task 10, structured-plan-engine-907066) заменяют прежние
   * `planApproved`/`goalMaxIterations`: [AgentMode.GOAL] читает [planId] (null = StructuredPlanOrchestrator
   * сам резолвит план диалога) и многомерный [PlanRunBudget] вместо одного числа витков. Оба
   * игнорируются CHAT/TUTOR/REFLECT.
   */
  fun launchTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planId: String? = null,
    budget: PlanRunBudget = PlanRunBudget(),
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    scope.launch {
      startTurn(
        conversationId, userText, mode, planId, budget,
        persistUserMessage = true, systemPromptExtra = systemPromptExtra, executionMode = executionMode,
      )
    }
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

  /**
   * Отменяет ход и ДОЖИДАЕТСЯ его фактической остановки, после чего забывает сессию. Отличается от
   * [cancel] тем, что [cancel] возвращается сразу: вызывающему, который следом сносит сам диалог,
   * этого мало — у `messages` внешний ключ на `conversations` с CASCADE, и удаление из-под ещё
   * живого хода роняет запись ответа по constraint. Единственный такой вызывающий сегодня —
   * уборка тестового диалога конструктора (`AgentBuilderTestViewModel.onCleared`).
   */
  suspend fun cancelAndAwait(conversationId: String) {
    val job = mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: return@withLock null
      sessionsFlow.update { it - conversationId }
      session.job
    }
    job?.cancelAndJoin()
  }

  /**
   * Повтор последнего хода после ошибки: реплика пользователя уже в истории, addMessage не повторяем.
   * Повторяет и [TurnSession.lastExecutionMode] прошлой попытки — «Повторить» не должен переиграть
   * PREVIEW-ход LIVE-ом (или наоборот).
   *
   * Task 10 (structured-plan-engine-907066): повторяет [TurnSession.lastPlanId]/[TurnSession.lastBudget]
   * как есть — никакой спецлогики больше не нужно (в отличие от прежнего жёсткого `planApproved=false`):
   * approval теперь persisted-состояние PlanRepository, а не флаг хода, так что ретрай план-мода
   * просто заново вызывает propose()/executeGoal() с теми же аргументами, что и оригинальный ход.
   */
  fun retry(conversationId: String) {
    scope.launch {
      val session = sessionsFlow.value[conversationId] ?: return@launch
      val text = session.lastUserText ?: return@launch
      startTurn(
        conversationId, text, session.lastMode, session.lastPlanId, session.lastBudget,
        persistUserMessage = false, executionMode = session.lastExecutionMode,
      )
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
   * чтобы бейдж «готово» в списке погас. Running/Failed/blank-error не трогаем: они ещё нужны
   * экрану (индикация, ретрай).
   *
   * До Task 5 (structured-plan-engine-907066) сюда же входил `!status.planAwaitingApproval` — план,
   * ждущий одобрения, держал сессию видимой, чтобы approval-бар не исчезал сам. Тот флаг убран
   * из TurnStatus.Completed вместе с magic-message approvePlan; «ждёт ли решения план» теперь
   * состояние PlanRepository (см. PlanCommands), а не этой сессии, так что markSeen её больше не
   * особит — чисто завершённый ход (включая план-фазу без ошибки) убирается как обычно.
   */
  fun markSeen(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        val status = session.status.value
        val cleanlyDone = status is TurnStatus.Completed && status.error == null && !status.canRetry
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
   * Единая точка старта (отправка/ретрай). Прошлый ход этого же диалога мог ещё
   * выполнять инструмент или запись — гасим его и ждём внутри нового job, прежде чем стартовать
   * (перенос семантики прежнего ChatViewModel.launchTurn, но с ключом-диалогом вместо одного
   * глобального activeJob).
   */
  internal suspend fun startTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    persistUserMessage: Boolean,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    // Захват ДО мьютекса: acquire уходит в Android (старт сервиса), держать на нём мьютекс
    // менеджера незачем — он сериализует только подмену job. Отказ системы не ошибка: ход просто
    // пойдёт без защиты, ровно как до появления этой фичи.
    val hasProtection = lifeSupport.acquire(conversationId)
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
        try {
          previous?.join()
          runTurn(session, userText, mode, planId, budget, persistUserMessage, systemPromptExtra, executionMode)
        } finally {
          // finally, а не хвост runTurn: отмена (Stop, новый ход) обязана отпустить защиту тоже,
          // иначе сервис с уведомлением переживёт ход, ради которого поднимался.
          if (hasProtection) lifeSupport.release(conversationId)
        }
      }
    }
  }

  /**
   * Система отобрала защиту (таймаут shortService). Гейт на видимости — ключевой: foreground-процесс
   * ОС не вытесняет, поэтому на экране защищать не от чего, и резать длинный GOAL-прогон там было бы
   * вредом без причины. В фоне же альтернатива «оставить ход без защиты» означает тихую пропажу
   * ответа, поэтому валим явно и с «Повторить».
   */
  private suspend fun failProtectedTurns(protectedIds: Set<String>) {
    if (foreground.isInForeground()) return
    mutex.withLock {
      protectedIds.forEach { convId ->
        val session = sessionsFlow.value[convId] ?: return@forEach
        if (session.status.value !is TurnStatus.Running) return@forEach
        // cancelAndJoin, а не cancel: ход не держит этот мьютекс во время исполнения, поэтому между
        // «увидел Running» и записью Failed он успевал бы дописать Completed вместе с ответом в БД —
        // и мы затёрли бы валидный результат ошибкой ровно в момент, когда ход и так почти доехал.
        // Ждём фактической остановки и перепроверяем статус.
        session.job?.cancelAndJoin()
        if (session.status.value !is TurnStatus.Running) return@forEach
        session.statusFlow.value =
          TurnStatus.Failed(TurnTexts.PROTECTION_EXPIRED, canRetry = session.lastUserText != null)
      }
    }
  }

  private suspend fun runTurn(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    persistUserMessage: Boolean,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    val convId = session.conversationId
    resetStreaming(session)
    session.toolTraceFlow.value = emptyList()
    session.responseStartTime = System.currentTimeMillis()
    session.statusFlow.value = TurnStatus.Running
    session.lastMode = mode
    session.lastPlanId = planId
    session.lastBudget = budget
    session.lastExecutionMode = executionMode
    try {
      if (persistUserMessage) {
        // Новый ход — новая попытка: ретрай-контекст валиден только после успешного addMessage.
        session.lastUserText = null
        session.lastUserMessageId = null
        val userMsgId = persistence.addUserMessage(convId, userText)
        session.lastUserText = userText
        session.lastUserMessageId = userMsgId
      }
      collectTurnEvents(session, userText, mode, planId, budget, systemPromptExtra, executionMode)
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
    planId: String?,
    budget: PlanRunBudget,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    // Провайдер берём из Настроек на каждый ход. Раньше здесь был жёстко прошитый NIM с пометкой
    // «выпилим позже» — оставить его сейчас значило бы сделать селектор провайдера контролом
    // без эффекта.
    val flow = executor.execute(session.conversationId, userText, selectedProvider(), mode, planId, budget, systemPromptExtra, executionMode)
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

        is AgentEvent.Completed -> handleCompleted(session, event.result)

        is AgentEvent.Error -> finishFailed(session, event.message)

        is AgentEvent.StateChanged -> when (event.state) {
          // Новый виток ReAct копит текст заново — чистим ТОЛЬКО буфер. Видимый пузырь НЕ гасим:
          // между витками там стоит прогресс вызова инструмента, и его перезапишет первый TextDelta
          // нового витка. Раньше здесь было ещё и обнуление пузыря (resetStreaming), из-за чего
          // преамбула прошлого витка «затиралась в пустоту» — выглядело как «ответ пишется, потом
          // исчезает» (виден только на ходах с инструментом: без него виток один, стирать нечего).
          AgentState.STREAMING -> session.streamBuffer.reset()
          // Агент ушёл исполнять инструмент: на месте пузыря показываем прогресс, а не преамбулу.
          // Буфер чистим, чтобы поздний flush не вернул преамбулу поверх прогресса.
          AgentState.EXECUTING_TOOL -> {
            session.streamBuffer.reset()
            session.streamingTextFlow.value = TurnTexts.TOOL_PROGRESS
          }
          // Стрим витка закончился (FINISHED и пр.): дофлашиваем хвост, не прошедший гейт частоты.
          else -> session.streamBuffer.flush()?.let { session.streamingTextFlow.value = it }
        }

        // Task 10 (structured-plan-engine-907066): PLAN/GOAL терминальные события StructuredPlanOrchestrator
        // — свой текст, без resolveCompletedTurn (тот обслуживает только CHAT/REFLECT/TUTOR, которые
        // сюда как AgentEvent.Completed приходят; PLAN/GOAL с этих режимов не приходят вовсе).
        is AgentEvent.PlanProposed -> finishPlanMessage(session, TurnTexts.planProposedMessage(event.planId, event.version))
        is AgentEvent.PlanProgressed -> {
          // Сначала сам ответ шага (ради него план и запускали), потом строка прогресса. Раньше
          // писалась только строка прогресса, и результат шага пропадал целиком.
          persistPlanStepOutputs(event.result.stepOutputs) { text, card ->
            persistence.addAssistantMessage(session.conversationId, text, card)
          }
          finishPlanMessage(
            session,
            TurnTexts.planProgressMessage(event.result),
            toolTrace = event.result.stepOutputs.flatMap { it.toolTrace },
          )
        }
        is AgentEvent.PlanBlocked -> finishPlanMessage(session, event.reason)

        // Уточняем прогресс именем инструмента (EXECUTING_TOOL мгновением раньше показал общий текст).
        is AgentEvent.ToolStarted -> session.streamingTextFlow.value = TurnTexts.toolProgress(event.name)

        // Ходу не нужны: бар approval'а ChatViewModel берёт из ToolApprovalCoordinator.pending
        // (фильтруя по своему conversationId), а не из потока событий хода. Ветка явная, а не else:
        // новое событие цикла обязано уронить компиляцию здесь, а не потеряться молча.
        is AgentEvent.ToolApprovalRequired -> Unit
      }
    }
  }

  private suspend fun handleCompleted(
    session: TurnSession,
    result: AgentResult,
  ) {
    val convId = session.conversationId
    // Все решения (blank-ошибки, карточки) — в чистой политике; здесь только применяем: записать,
    // обновить статус, уведомить. Этот путь (AgentEvent.Completed) обслуживает только CHAT/REFLECT/
    // TUTOR: PLAN/GOAL теперь заканчиваются PlanProposed/PlanProgressed/PlanBlocked/Error (см.
    // collectTurnEvents) — Task 15 (structured-plan-engine-907066) убрал mode-зависимые ветки из
    // resolveCompletedTurn вместе со старым prompt-only PLAN/GOAL, так что mode ему больше не нужен.
    val outcome = resolveCompletedTurn(result)
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
      // «Повторить» — когда есть ошибка пустого ответа и в сессии остался текст пользователя.
      canRetry = outcome.blankError != null && session.lastUserText != null,
    )
    notifyIfBackground(convId, preview = outcome.responseText.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
  }

  /**
   * Task 10 (structured-plan-engine-907066): shared tail for [AgentEvent.PlanProposed]/
   * [AgentEvent.PlanProgressed]/[AgentEvent.PlanBlocked] — persists [text] as a plain assistant
   * message (no card yet — a dedicated `agent_plan` PlanCard is Task 12's Plan UI, not this task)
   * and completes the session, mirroring [handleCompleted]'s own "insert, update status, notify"
   * bookkeeping. No token stats: a plan turn's LLM calls happen inside
   * `com.aiopenpocket.agent.core.plan.planner.PlanPlanner`/`com.aiopenpocket.agent.core.plan.execution.PlanExecutor`,
   * not this loop, and are not surfaced here. [toolTrace] — трейс исполненных шагов (GOAL), чтобы
   * панель инструментов показывала, что шаг реально вызывал; у PLAN/NeedsInfo он пуст. Always
   * `canRetry = false` — unlike a blank/network error, there is nothing to retry: the user can just
   * send another PLAN/GOAL turn.
   */
  private suspend fun finishPlanMessage(session: TurnSession, text: String, toolTrace: List<ToolTrace> = emptyList()) {
    val convId = session.conversationId
    val insertedId = persistence.addAssistantMessage(convId, text, cardSpec = null)
    session.toolTraceFlow.value = toolTrace
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = 0,
      contextTokensUsed = 0,
      error = null,
      canRetry = false,
    )
    notifyIfBackground(convId, preview = text.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
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
