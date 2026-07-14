package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.loop.ToolTrace

/**
 * Per-step run budget (design spec `2026-07-12-structured-plan-engine-design.md` section 10:
 * "Бюджет задаётся раздельно... Это заменяет один неоднозначный `goalMaxIterations`") — replaces the
 * single ambiguous whole-turn `goalMaxIterations` with independently-tunable knobs. Every field
 * except [maxStepsPerRun] is scoped to ONE [PlanExecutor.execute] call for ONE step; [maxStepsPerRun]
 * is Task 10's own addition — the one dimension that spans a WHOLE `PlanRunner.run` (see its own kdoc).
 *
 * [maxLlmIterationsPerStep] feeds [com.aiopenpocket.agent.loop.ReactLoopRunner.run]'s own
 * `maxIterations`; [maxToolCallsPerRun] feeds its new `maxToolCalls` (this same task's Step 4). Both
 * are enforced INSIDE the loop. [maxRetriesPerStep] and [deadlineAt] are enforced by [PlanExecutor]
 * itself, BEFORE ever invoking the loop — see [PlanExecutor.execute]'s pre-flight checks.
 */
data class PlanRunBudget(
  val maxLlmIterationsPerStep: Int = 3,
  val maxToolCallsPerRun: Int = 5,
  val maxRetriesPerStep: Int = 2,
  val deadlineAt: Long? = null,
  /**
   * Task 10 (`com.aiopenpocket.agent.core.plan.execution.PlanRunner`): how many
   * [PlanExecutor.execute] calls one `PlanRunner.run` may make before stopping with
   * [com.aiopenpocket.agent.core.plan.model.PlanRunOutcome.PARTIAL], even if further runnable/due
   * steps remain. Defaults to 1 so the ordinary interactive GOAL chat turn (design spec section 9:
   * "GOAL... исполняет только этот step") advances exactly one step per user "send" — a background
   * caller (Task 11's heartbeat, or a future "catch up" action) asks for a larger value explicitly
   * when it wants one run to sweep several due steps.
   */
  val maxStepsPerRun: Int = 1,
)

/**
 * What [PlanExecutor.execute] produced for ONE step run (design spec section 10) — the "raw run
 * result" the class kdoc promises, deliberately NOT a verified/evidence-backed outcome. Task 8's
 * `PlanVerifier` is the seam that turns this into [com.aiopenpocket.agent.core.plan.model.PlanEvidence]
 * and a real completion decision; this type only reports what the loop itself did.
 */
data class PlanStepExecutionResult(
  val stepId: String,
  /** The model's final-turn text, or the accumulated transcript on a budget stop — mirrors [com.aiopenpocket.agent.loop.AgentResult.text]. */
  val text: String,
  val toolTrace: List<ToolTrace>,
  /**
   * True when this run never reached a model-driven finish: either a pre-flight budget refusal
   * (retries already exhausted, or [PlanRunBudget.deadlineAt] already passed — the loop was never
   * even invoked), or the loop itself stopped on [PlanRunBudget.maxLlmIterationsPerStep]/
   * [PlanRunBudget.maxToolCallsPerRun] mid-run. `false` on a clean finish OR an [error].
   */
  val stoppedByBudget: Boolean,
  /** Set on an LLM/stream error ([com.aiopenpocket.agent.loop.AgentState.ERROR]) or a pre-flight budget refusal; null otherwise. */
  val error: String?,
)
