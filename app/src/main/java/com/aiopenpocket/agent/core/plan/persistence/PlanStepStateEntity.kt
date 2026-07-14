package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.StepStatus

/**
 * Room row for one [PlanStepState] — runtime progress of a single step within one specific
 * ([planId], [version]) (design spec section 5) — normalized and updated independently of the
 * immutable [PlanVersionEntity] it belongs to, so bumping [attempts]/[status] never touches
 * `plan_versions.snapshotJson`. This is explicitly a NON-MATERIAL write (design spec section 6): unlike
 * [PlanVersionEntity], [PlanRepository.updateStepState] freely upserts this row regardless of plan
 * approval state.
 *
 * Composite primary key ([planId], [version], [stepId]) — never confuses a superseded version's old
 * progress rows with the currently approved version's, exactly per [PlanStepState]'s own kdoc.
 */
@Entity(
  tableName = "plan_step_states",
  primaryKeys = ["planId", "version", "stepId"],
  foreignKeys = [
    ForeignKey(
      entity = AgentPlanEntity::class,
      parentColumns = ["id"],
      childColumns = ["planId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("planId", "version", "status", "nextDueAt")],
)
data class PlanStepStateEntity(
  val planId: String,
  val version: Int,
  val stepId: String,
  val status: String,
  val attempts: Int,
  val nextDueAt: Long?,
  val startedAt: Long?,
  val completedAt: Long?,
) {
  fun toDomain(): PlanStepState = PlanStepState(
    planId = planId,
    version = version,
    stepId = stepId,
    status = StepStatus.valueOf(status),
    attempts = attempts,
    nextDueAt = nextDueAt,
    startedAt = startedAt,
    completedAt = completedAt,
  )

  companion object {
    fun fromDomain(state: PlanStepState): PlanStepStateEntity = PlanStepStateEntity(
      planId = state.planId,
      version = state.version,
      stepId = state.stepId,
      status = state.status.name,
      attempts = state.attempts,
      nextDueAt = state.nextDueAt,
      startedAt = state.startedAt,
      completedAt = state.completedAt,
    )
  }
}
