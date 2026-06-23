package com.aiopenpocket.agent.loop

/** An ordered execution plan produced by the planner phase of [PlanExecuteOrchestrator]. */
data class Plan(val steps: List<PlanStep>)

data class PlanStep(
  /** 1-based position, used for display and for referencing prior steps. */
  val id: Int,
  val title: String,
  val detail: String? = null,
  val status: PlanStepStatus = PlanStepStatus.PENDING,
  /** The executor's short result text, filled once the step runs. */
  val result: String? = null,
)

enum class PlanStepStatus { PENDING, RUNNING, DONE, FAILED }
