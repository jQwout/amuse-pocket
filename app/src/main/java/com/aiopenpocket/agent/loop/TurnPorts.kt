package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.flow.Flow

/**
 * The turn execution port — a narrow seam between TurnManager and AgentLoopOrchestrator/
 * ReflectionOrchestrator/StructuredPlanOrchestrator, so the manager can be tested with a scripted fake,
 * without an LLM or Room.
 *
 * Task 10 (structured-plan-engine-907066): `planApproved`/`goalMaxIterations` are gone — approval is
 * no longer a per-turn flag (see PlanCommands), and the [AgentMode.GOAL] budget is now the
 * multi-dimensional [PlanRunBudget], not a single `goalMaxIterations`. [planId] is the plan GOAL runs
 * (null = StructuredPlanOrchestrator resolves the conversation's plan itself); [budget] is used only by
 * the PLAN/GOAL branch of the dispatch in TurnModule, CHAT/TUTOR/REFLECT do not read it.
 */
fun interface TurnExecutor {
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    /** A trusted addition to the system prompt for this turn (e.g. the context of the current lesson step). */
    systemPromptExtra: String?,
    /** LIVE (the default of the calling TurnManager) or PREVIEW — see ExecutionPolicy. */
    executionMode: ExecutionMode,
  ): Flow<AgentEvent>
}

/** The little of ConversationRepository that a turn needs: write messages and read the title. */
interface TurnPersistence {
  suspend fun addUserMessage(conversationId: String, text: String): String
  suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String
  suspend fun conversationTitle(conversationId: String): String?
}

/** Is the app on screen right now? The gate for the push about a background turn finishing. */
fun interface ForegroundState {
  fun isInForeground(): Boolean
}

/**
 * Is this tool-approval request still waiting for an answer? Bound to
 * [com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator.isPending] in `TurnModule`, and a port
 * rather than the coordinator itself for the same reason as [ForegroundState]/[TurnNotifier]:
 * [TurnManager] stays testable without the policy machinery.
 *
 * It exists because the foreground check for the "needs permission" push cannot be a single shot at
 * the moment the request appears — see [TurnManager]'s own comment on that branch.
 */
fun interface PendingApprovals {
  fun isPending(requestId: String): Boolean
}

/** Shows the "the turn finished while the app was in the background" notification. */
fun interface TurnNotifier {
  fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean)

  /**
   * The agent needs permission while the app is minimized. Deleting and sending anything outside
   * always wait for "Allow", and without a push the request just expires with nothing done and
   * nobody asked. By default it is the same turn push, leading to the chat: that is where the
   * confirmation bar is.
   */
  fun approvalNeeded(conversationId: String, conversationTitle: String?, action: String) =
    turnFinished(conversationId, conversationTitle, tr("Агенту нужно разрешение: $action. Открой чат, чтобы ответить.", "The agent needs your permission: $action. Open the chat to answer."), false)
}

/**
 * Protects a turn from process eviction while the app is in the background (spec
 * `docs/superpowers/specs/2026-07-28-turn-foreground-service-design.md`). A separate port rather than
 * a direct Android call for the same reason as [ForegroundState]/[TurnNotifier]: [TurnManager] must be
 * testable without Android.
 */
interface TurnLifeSupport {
  /**
   * Requests protection for [conversationId]. `false` — the system refused; the typical case is a turn
   * starting while the app is already in the background (since API 31 a foreground service cannot be
   * started from there). The turn still runs, just without the guarantee — exactly as before this
   * feature.
   */
  suspend fun acquire(conversationId: String): Boolean

  /** Releases the protection idempotently. Call ONLY if [acquire] returned `true`. */
  fun release(conversationId: String)

  /**
   * Registers the reaction to "the system took the protection away" (the `shortService` timeout). The
   * argument is the conversationIds that were protected at that moment. Called once, when the graph is
   * built.
   */
  fun onProtectionLost(handler: (Set<String>) -> Unit)
}
