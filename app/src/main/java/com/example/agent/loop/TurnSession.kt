package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Статус хода в диалоге. Отсутствие сессии в мапе менеджера = Idle (хода нет и не было
 * с последнего просмотра). Running → Completed | Failed; отменённый ход просто удаляется.
 */
sealed interface TurnStatus {
  data object Running : TurnStatus

  /**
   * Ход завершился. [insertedMessageId] — id сохранённого ассистентского сообщения; null, если
   * вставки не было (пустой ответ). Пузырь стрима экран держит, ПОКА сообщение не приехало в окно
   * ленты, — это заменяет прежний pendingHandoffId и убирает «мигание» конца хода.
   *
   * Больше не несёт план-approval флаг (Task 5, structured-plan-engine-907066): «план ждёт
   * одобрения» — теперь состояние persisted AgentPlan в PlanRepository, а не per-turn сигнал этого
   * класса. См. com.aiopenpocket.agent.core.plan.execution.PlanCommands и ChatUiState.pendingPlanApproval.
   */
  data class Completed(
    val insertedMessageId: String?,
    val responseTimeMs: Long,
    val completionTokens: Int,
    val contextTokensUsed: Int,
    /** Сообщение об ошибке «пустой ответ» (иначе null). */
    val error: String?,
    val canRetry: Boolean,
  ) : TurnStatus

  data class Failed(val message: String, val canRetry: Boolean) : TurnStatus
}

/**
 * Состояние одного хода агента, отвязанное от экрана: живёт в TurnManager (appScope), пока
 * пользователь ходит по приложению. Ретрай-контекст тоже здесь — «Повторить» переживает
 * пересоздание ViewModel (раньше терялся при выходе с экрана).
 */
class TurnSession internal constructor(
  val conversationId: String,
  monotonicNowMs: () -> Long,
) {
  internal val statusFlow = MutableStateFlow<TurnStatus>(TurnStatus.Running)
  val status: StateFlow<TurnStatus> = statusFlow.asStateFlow()

  // Стрим — отдельный поток, как раньше в ChatViewModel: дельты частые, рекомпозируем только пузырь.
  internal val streamingTextFlow = MutableStateFlow("")
  val streamingText: StateFlow<String> = streamingTextFlow.asStateFlow()

  internal val toolTraceFlow = MutableStateFlow<List<ToolTrace>>(emptyList())
  val toolTrace: StateFlow<List<ToolTrace>> = toolTraceFlow.asStateFlow()

  @Volatile internal var job: Job? = null
  internal val streamBuffer = StreamingTextBuffer(monotonicNowMs)
  @Volatile internal var responseStartTime = 0L

  // Ретрай-контекст последнего хода (см. ChatViewModel до переезда: lastUserText и др.).
  //
  // @Volatile везде: эти поля пишет ход на Dispatchers.Default (TurnManager.runTurn), а читают и
  // пишут интенты с других потоков — retry()/invalidateRetry()/invalidateRetryFor() работают БЕЗ
  // мьютекса, которым TurnManager защищает мапу сессий. Без volatile «Повторить» мог читать stale
  // значение (или не увидеть сброс) — редко, недетерминированно и ровно на границе смены хода.
  @Volatile internal var lastUserText: String? = null
  @Volatile internal var lastUserMessageId: String? = null
  @Volatile internal var lastMode: AgentMode = AgentMode.CHAT
  /**
   * Task 10 (structured-plan-engine-907066): заменяют прежний `lastGoalMaxIterations` — GOAL больше
   * не описывается одним числом витков, а [planId]/[budget] нужны и PLAN-ретраю (какой план
   * ре-предложить), и GOAL-ретраю (какой план продолжить и в каких рамках). null-планId —
   * StructuredPlanOrchestrator сам резолвит план диалога, как и в оригинальном ходе.
   */
  @Volatile internal var lastPlanId: String? = null
  @Volatile internal var lastBudget: PlanRunBudget = PlanRunBudget()
  /** LIVE/PREVIEW последнего хода — «Повторить» переигрывает в том же режиме. */
  @Volatile internal var lastExecutionMode: ExecutionMode = ExecutionMode.LIVE
}
