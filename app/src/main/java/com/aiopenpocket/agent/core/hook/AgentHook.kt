package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153

/**
 * Lifecycle point in a turn a [AgentHook] can attach to. The declarative [HookEngine] (Task 8)
 * evaluates enabled hooks for the current agent whenever the runtime reaches one of these points.
 */
enum class HookEvent { BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_COMPLETE }

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
