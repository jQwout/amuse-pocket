package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanEvidence

/**
 * Room row for one [PlanEvidence] entry — a persisted reference to a real, observed fact (design spec
 * section 5/11), never free-text model prose. UUID [id] primary key (append-mostly, like
 * [com.aiopenpocket.agent.core.hook.AgentHookRunEntity]'s journal rows) rather than a composite key,
 * since many evidence rows can legitimately share the same ([planId], [version], [criterionId]) triple
 * (e.g. two separate tool results both supporting one criterion).
 *
 * [criterionId]/[stepId] are independently nullable, matching [PlanEvidence]'s own kdoc: one entry can
 * support a whole-plan criterion, document a single step's completion, both, or neither yet still be a
 * persisted audit record.
 */
@Entity(
  tableName = "plan_evidence",
  foreignKeys = [
    ForeignKey(
      entity = AgentPlanEntity::class,
      parentColumns = ["id"],
      childColumns = ["planId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("planId", "version", "criterionId")],
)
data class PlanEvidenceEntity(
  @PrimaryKey val id: String,
  val planId: String,
  val version: Int,
  val criterionId: String?,
  val stepId: String?,
  val source: String,
  val reference: String,
  val summary: String,
  val createdAt: Long,
) {
  fun toDomain(): PlanEvidence = PlanEvidence(
    id = id,
    planId = planId,
    version = version,
    criterionId = criterionId,
    stepId = stepId,
    source = EvidenceSource.valueOf(source),
    reference = reference,
    summary = summary,
    createdAt = createdAt,
  )

  companion object {
    fun fromDomain(evidence: PlanEvidence): PlanEvidenceEntity = PlanEvidenceEntity(
      id = evidence.id,
      planId = evidence.planId,
      version = evidence.version,
      criterionId = evidence.criterionId,
      stepId = evidence.stepId,
      source = evidence.source.name,
      reference = evidence.reference,
      summary = evidence.summary,
      createdAt = evidence.createdAt,
    )
  }
}
