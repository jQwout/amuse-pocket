package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361

/** An ordered execution plan produced by the planner phase of [PlanExecuteOrchestrator]. */
data class Plan(val steps: List<PlanStep>)

data class PlanStep(
  /** 1-based position, used for display and for referencing prior steps. */
  val id: Int,
  val title: String,
  val detail: String? = null,
)
