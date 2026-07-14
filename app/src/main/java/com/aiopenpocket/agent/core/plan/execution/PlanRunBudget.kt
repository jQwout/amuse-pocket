package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.loop.ToolTrace

/**
 * Per-step run budget (design spec `2026-07-12-structured-plan-engine-design.md` section 10:
 * "Бюджет задаётся раздельно... Это заменяет один неоднозначный `goalMaxIterations`") — replaces the
 * single ambiguous whole-turn `goalMaxIterations` with four independently-tunable knobs, all scoped
 * to ONE [PlanExecutor.execute] call for ONE step, never to a whole GOAL run across many steps (that
 * whole-run budget/loop belongs to `PlanRunner`, Task 10).
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
