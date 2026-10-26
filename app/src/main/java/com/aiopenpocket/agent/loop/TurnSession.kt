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
 * Status of a turn in a conversation. No session in the manager's map = Idle (no turn now and none
 * since the last view). Running → Completed | Failed; a cancelled turn is simply removed.
 */
sealed interface TurnStatus {
  data object Running : TurnStatus

  /**
   * The turn finished. [insertedMessageId] is the id of the saved assistant message; null if nothing
   * was inserted (an empty answer). The screen keeps the stream bubble UNTIL the message reaches the
   * feed window — this replaces the old pendingHandoffId and removes the end-of-turn "flicker".
   *
   * No longer carries a plan-approval flag (Task 5, structured-plan-engine-907066): "the plan is
   * waiting for approval" is now the state of the persisted AgentPlan in PlanRepository, not a
   * per-turn signal of this class. See com.aiopenpocket.agent.core.plan.execution.PlanCommands and
   * ChatUiState.pendingPlanApproval.
   */
  data class Completed(
    val insertedMessageId: String?,
    val responseTimeMs: Long,
    val completionTokens: Int,
    val contextTokensUsed: Int,
    /** The "empty answer" error message (null otherwise). */
    val error: String?,
    val canRetry: Boolean,
  ) : TurnStatus

  data class Failed(val message: String, val canRetry: Boolean) : TurnStatus
}

/**
 * State of one agent turn, decoupled from the screen: it lives in TurnManager (appScope) while the
 * user moves around the app. The retry context is here too — "Retry" survives the ViewModel being
 * recreated (it used to be lost on leaving the screen).
 */
class TurnSession internal constructor(
  val conversationId: String,
  monotonicNowMs: () -> Long,
) {
  internal val statusFlow = MutableStateFlow<TurnStatus>(TurnStatus.Running)
  val status: StateFlow<TurnStatus> = statusFlow.asStateFlow()

  // The stream is a separate flow, as it used to be in ChatViewModel: deltas are frequent, only the bubble recomposes.
  internal val streamingTextFlow = MutableStateFlow("")
  val streamingText: StateFlow<String> = streamingTextFlow.asStateFlow()

  internal val toolTraceFlow = MutableStateFlow<List<ToolTrace>>(emptyList())
  val toolTrace: StateFlow<List<ToolTrace>> = toolTraceFlow.asStateFlow()

  @Volatile internal var job: Job? = null
  internal val streamBuffer = StreamingTextBuffer(monotonicNowMs)
  @Volatile internal var responseStartTime = 0L

  // Retry context of the last turn (see ChatViewModel before the move: lastUserText etc.).
  //
  // @Volatile everywhere: these fields are written by the turn on Dispatchers.Default
  // (TurnManager.runTurn), while intents read and write them from other threads —
  // retry()/invalidateRetry()/invalidateRetryFor() run WITHOUT the mutex TurnManager guards the
  // session map with. Without volatile "Retry" could read a stale value (or miss a reset) — rarely,
  // nondeterministically, and exactly at the boundary between turns.
  @Volatile internal var lastUserText: String? = null
  @Volatile internal var lastUserMessageId: String? = null
  @Volatile internal var lastMode: AgentMode = AgentMode.CHAT
  /**
   * Task 10 (structured-plan-engine-907066): replace the old `lastGoalMaxIterations` — GOAL is no
   * longer described by a single iteration count, and [planId]/[budget] are needed both by a PLAN
   * retry (which plan to propose again) and a GOAL retry (which plan to continue and within what
   * limits). A null planId means StructuredPlanOrchestrator resolves the conversation's plan itself,
   * as in the original turn.
   */
  @Volatile internal var lastPlanId: String? = null
  @Volatile internal var lastBudget: PlanRunBudget = PlanRunBudget()
  /** LIVE/PREVIEW of the last turn — "Retry" replays in the same mode. */
  @Volatile internal var lastExecutionMode: ExecutionMode = ExecutionMode.LIVE
}
