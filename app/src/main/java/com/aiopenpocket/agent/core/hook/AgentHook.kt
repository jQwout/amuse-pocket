package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

/**
 * Lifecycle point in a turn (or, since Task 9 of the Structured Plan Engine, a plan run) a
 * [AgentHook] can attach to. The declarative [HookEngine] evaluates enabled hooks for the current
 * agent whenever the runtime reaches one of these points.
 *
 * [BEFORE_TURN]/[AFTER_TURN]/[BEFORE_TOOL]/[AFTER_TOOL]/[ON_ERROR]/[ON_COMPLETE] are the original
 * (Task 8) low-level ReAct/tool lifecycle, fired by [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * around every turn/tool regardless of whether a PLAN/GOAL run is in progress.
 *
 * [ON_PLAN_CREATED]/[ON_PLAN_APPROVED]/[BEFORE_PLAN_STEP]/[AFTER_PLAN_STEP]/[ON_PLAN_BLOCKED]/
 * [ON_PLAN_COMPLETED] (Task 9; design spec `2026-07-12-structured-plan-engine-design.md` section
 * 12.2) are the plan-lifecycle events, fired by [com.aiopenpocket.agent.core.plan.execution.PlanCommands]/
 * [com.aiopenpocket.agent.core.plan.execution.PlanExecutor] using the exact same [HookEngine] —
 * there is no second hook engine. A [BEFORE_TURN]/[BEFORE_TOOL]/etc. firing can still happen INSIDE
 * a plan step (the step's own turn runs through [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * unchanged), so a plan step run fires BOTH families of event, nested: `BEFORE_PLAN_STEP` around
 * the whole step, `BEFORE_TURN`/`BEFORE_TOOL`/etc. around its inner turn(s).
 */
enum class HookEvent {
  BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_COMPLETE,
  ON_PLAN_CREATED, ON_PLAN_APPROVED, BEFORE_PLAN_STEP, AFTER_PLAN_STEP, ON_PLAN_BLOCKED, ON_PLAN_COMPLETED,
}

/**
 * What a matched [AgentHook] does. No hook may run arbitrary user code or call the LLM itself
 * (see Global Constraints in the user-agent-builder plan) — every action here is interpreted and
 * enforced by code in the [HookEngine], never evaluated as script.
 */
enum class HookActionType { ADD_INSTRUCTION, REQUIRE_APPROVAL, ALLOW, DENY, LOG, STOP }

/**
 * Declarative lifecycle rule for a user agent: "when [event] fires and [conditionType]/
 * [conditionJson] matches, do [actionType] with [actionJson]". [conditionJson]/[actionJson] are
 * opaque, engine-interpreted payloads (shape depends on [conditionType]/[actionType]) rather than
 * typed sub-models, so new condition/action shapes don't require a schema migration.
 *
 * Domain type only as of Task 1 (introduced early so [com.aiopenpocket.agent.core.agent.AgentDefinition]
 * has its final shape from the start) — no engine behavior exists yet; that lands in Task 8 along
 * with [AgentHook] persistence (`AgentHookEntity`/`AgentHookDao`/`AgentHookRepository`). The preset
 * adapter always supplies `hooks = emptyList()`.
 */
data class AgentHook(
  val id: String,
  val agentId: String,
  val event: HookEvent,
  val conditionType: String,
  val conditionJson: String,
  val actionType: HookActionType,
  val actionJson: String,
  val priority: Int,
  val enabled: Boolean,
)
