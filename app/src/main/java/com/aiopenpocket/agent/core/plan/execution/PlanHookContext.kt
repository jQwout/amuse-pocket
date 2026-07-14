package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.hook.HookContext
import com.aiopenpocket.agent.core.hook.HookDecision
import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import java.util.UUID

/**
 * Everything one plan-lifecycle [HookEvent] firing can see (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 12.2; plan Task 9 Step 2's sketch,
 * reproduced here). A small typed builder for the SAME [HookContext] shape [HookEngine.matched]
 * already accepts (see [toHookContext]) — Task 9 does not teach the engine a second context type,
 * per its own "reuse the exact firing pattern, do not build a second hook engine" constraint. Purely
 * a plain data snapshot: no [com.aiopenpocket.agent.core.plan.persistence.PlanRepository]/DAO
 * reference ever reaches [HookEngine] through this, matching [HookContext]'s own "declarative rules
 * over a small, stable surface" boundary.
 *
 * [stepId]/[stepStatus] are null for the two whole-plan events that have no single step in play yet
 * ([HookEvent.ON_PLAN_CREATED]/[HookEvent.ON_PLAN_APPROVED]). [background] mirrors
 * [com.aiopenpocket.agent.core.plan.execution.PlanExecutor.execute]'s own `interactiveApproval`
 * (`background = !interactiveApproval`) — every plan step run is background/headless today (that
 * parameter's own kdoc), so [HookEvent.BEFORE_PLAN_STEP]'s `REQUIRE_APPROVAL` action resolves to
 * "blocked" rather than suspending for a UI that cannot answer (design spec section 12.2: "REQUIRE_APPROVAL
 * в background становится blocked; никогда не приостанавливает WorkManager в ожидании UI").
 */
data class PlanHookContext(
  val planId: String,
  val planVersion: Int,
  val planStatus: PlanStatus,
  val stepId: String? = null,
  val stepStatus: StepStatus? = null,
  val evidenceIds: List<String> = emptyList(),
  val background: Boolean = true,
)

/** Folds [this] into the engine's actual [HookContext] — the one shape [HookEngine.matched] accepts, turn-level or plan-level alike. */
private fun PlanHookContext.toHookContext(): HookContext = HookContext(
  planId = planId,
  planVersion = planVersion,
  stepId = stepId,
  planStatus = planStatus,
  stepStatus = stepStatus,
  evidenceIds = evidenceIds,
  background = background,
)

/**
 * Fires [event] for [hooks] and journals every firing — the exact same two-step pattern
 * [com.aiopenpocket.agent.loop.ReactLoopRunner]'s own private `fireHook` uses for turn-level hooks
 * ([HookEngine.matched] then [AgentHookRepository.recordRun] per [HookEngine.Firing], folded via
 * [HookEngine.toDecision]), reused verbatim rather than a second hook engine. Unlike
 * `ReactLoopRunner.fireHook`, this is a shared top-level function rather than a closure over one
 * loop run: plan-lifecycle events fire from more than one class ([PlanExecutor], [PlanCommands]),
 * each scoped to its own short-lived unit of work ([runId] identifies one step attempt or one
 * command call, not a whole GOAL run across many steps — that belongs to `PlanRunner`, Task 10).
 *
 * [hooks] must already be scoped to the owning agent (mirrors `ReactLoopRunner`'s own "hooks comes
 * from the immutable per-turn snapshot, never re-queried mid-turn" rule): [PlanExecutor] reuses its
 * `agent: AgentDefinition?` parameter's [AgentHook] list directly; [PlanCommands] has no loaded
 * `AgentDefinition` at all (only a `planId`/`agentId`), so it fetches
 * [AgentHookRepository.getForAgent] once per command instead. Short-circuits to a neutral
 * [HookDecision] without ever touching [HookEngine] or the journal when [hooks] is empty — the same
 * strict no-op guarantee `ReactLoopRunner.fireHook` gives a hook-less/absent agent.
 *
 * Declarative-only, by construction: the ONLY way this function can influence anything beyond the
 * `agent_hook_runs` journal is the returned [HookDecision] — it never calls an LLM or a tool itself,
 * never writes to `PlanRepository.saveProposal`/[com.aiopenpocket.agent.core.plan.model.PlanVersion],
 * and never adds [com.aiopenpocket.agent.core.plan.model.PlanEvidence]. Applying
 * [HookDecision.denied]/[HookDecision.stopped]/[HookDecision.approvalRequired]/
 * [HookDecision.addedInstructions] is entirely the CALLER's job — same split as
 * [com.aiopenpocket.agent.loop.ReactLoopRunner] itself keeps between firing a hook and acting on it.
 */
internal suspend fun firePlanHook(
  hookRepository: AgentHookRepository,
  hooks: List<AgentHook>,
  event: HookEvent,
  context: PlanHookContext,
  conversationId: String,
  runId: String,
): HookDecision {
  if (hooks.isEmpty()) return HookDecision()
  val firings = HookEngine.matched(hooks, event, context.toHookContext())
  for (firing in firings) {
    // Best-effort, same treatment as ReactLoopRunner.fireHook's own journal write: a DB hiccup must
    // never fail an otherwise-successful plan-lifecycle transition.
    runCatching {
      hookRepository.recordRun(
        AgentHookRunEntity(
          id = UUID.randomUUID().toString(),
          hookId = firing.hookId,
          conversationId = conversationId,
          runId = runId,
          timestamp = System.currentTimeMillis(),
          status = firing.actionType.name,
          summary = firing.detail,
          errorMessage = firing.error,
        ),
      )
    }
  }
  return HookEngine.toDecision(firings)
}
