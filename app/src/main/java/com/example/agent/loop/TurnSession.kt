package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153

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
   */
  data class Completed(
    val insertedMessageId: String?,
    val responseTimeMs: Long,
    val completionTokens: Int,
    val contextTokensUsed: Int,
    /** Сообщение об ошибке «пустой ответ» (иначе null). */
    val error: String?,
    val canRetry: Boolean,
    /** План-мод: показан непустой план, ждём «Выполнить»/«Поправить». */
    val planAwaitingApproval: Boolean,
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

  internal var job: Job? = null
  internal val streamBuffer = StreamingTextBuffer(monotonicNowMs)
  internal var responseStartTime = 0L

  // Ретрай-контекст последнего хода (см. ChatViewModel до переезда: lastUserText и др.).
  internal var lastUserText: String? = null
  internal var lastUserMessageId: String? = null
  internal var lastMode: AgentMode = AgentMode.CHAT
  internal var lastPlanApproved: Boolean = false
  internal var lastGoalMaxIterations: Int = 3
  /** LIVE/PREVIEW последнего хода — «Повторить» и одобрение плана переигрывают в том же режиме. */
  internal var lastExecutionMode: ExecutionMode = ExecutionMode.LIVE
}
