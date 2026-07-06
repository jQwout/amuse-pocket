package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

import android.os.SystemClock
import android.util.Log
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
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
      startTurn(conversationId, APPROVE_PLAN_MESSAGE, AgentMode.PLAN, planApproved = true, goalMaxIterations = goalIterations, persistUserMessage = true)
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
      val session = sessionsFlow.value[conversationId] ?: TurnSession(conversationId).also { created ->
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
      Log.e(TAG, "Agent turn error", e)
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
        is AgentEvent.TextDelta -> {
          // Аккумулируем в StringBuilder и публикуем под гейтом частоты (~20 пуб/сек):
          // toString() копирует весь накопленный текст, чаще глазу не нужно.
          session.streamingBuilder.append(event.content)
          val now = SystemClock.uptimeMillis()
          if (now - session.lastStreamPublishAt >= STREAM_PUBLISH_INTERVAL_MS) {
            session.lastStreamPublishAt = now
            session.streamingTextFlow.value = session.streamingBuilder.toString()
          }
        }

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
          else -> if (session.streamingBuilder.isNotEmpty()) {
            session.streamingTextFlow.value = session.streamingBuilder.toString()
          }
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
    // render_card-спеки становятся инлайн-карточками чата.
    val cardSpecs = result.toolTrace
      .filter { it.toolName == "render_card" }
      .mapNotNull { (it.result as? ToolResult.Success)?.data }

    // Фаза планирования: маркер [[NEED_INFO]] В НАЧАЛЕ ответа = уточняющий вопрос, не план.
    val planningPhase = mode == AgentMode.PLAN && !planApproved
    val rawText = result.text
    val needInfo = planningPhase && rawText.trimStart().startsWith(NEED_INFO_MARKER)
    val responseText = when {
      needInfo -> rawText.trimStart().removePrefix(NEED_INFO_MARKER).trim()
        .ifBlank { "Уточни, пожалуйста, детали задачи — что именно нужно сделать?" }

      planningPhase -> rawText.trim()
      else -> rawText
    }
    val insertedId = if (responseText.isNotBlank() || cardSpecs.isNotEmpty()) {
      persistence.addAssistantMessage(convId, responseText, cardSpecs.firstOrNull())
    } else {
      null
    }
    cardSpecs.drop(1).forEach { spec ->
      persistence.addAssistantMessage(convId, "", spec)
    }

    val planningDone = planningPhase && !needInfo && responseText.isNotBlank()
    val blankReply = responseText.isBlank() && cardSpecs.isEmpty()
    val blankError = when {
      planningPhase && blankReply -> "Модель не вернула план — попробуй ещё раз."
      blankReply -> "Модель вернула пустой ответ — попробуй ещё раз."
      else -> null
    }

    session.toolTraceFlow.value = result.toolTrace
    // streamingText НЕ сбрасываем: экран держит пузырь, пока сообщение не приедет в окно ленты
    // (замена pendingHandoffId — «мигание» конца хода не возвращается).
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = result.completionTokensEstimate,
      contextTokensUsed = result.promptTokensEstimate,
      error = blankError,
      canRetry = blankReply && session.lastUserText != null,
      planAwaitingApproval = planningDone,
    )
    notifyIfBackground(convId, preview = responseText.take(140).ifBlank { "Готово" }, failed = false)
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
    session.streamingBuilder.setLength(0)
    session.streamingTextFlow.value = ""
    session.lastStreamPublishAt = 0L
  }

  companion object {
    private const val TAG = "TurnManager"

    /** Маркер план-планирования: модель ставит его в начало ответа-вопроса (вырезается из текста). */
    internal const val NEED_INFO_MARKER = "[[NEED_INFO]]"

    /** Реплика, которой ход одобрения плана попадает в историю (перенос из ChatViewModel.approvePlan). */
    internal const val APPROVE_PLAN_MESSAGE = "Одобряю план, выполни его по шагам."

    /** Минимальный интервал публикации стрим-текста (~20 обновлений/сек). */
    private const val STREAM_PUBLISH_INTERVAL_MS = 50L
  }
}
