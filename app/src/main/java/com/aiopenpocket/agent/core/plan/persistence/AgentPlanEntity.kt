package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus

/**
 * Room row for one [AgentPlan] aggregate (design spec `2026-07-12-structured-plan-engine-design.md`
 * section 6) — the mutable metadata that survives across [PlanVersionEntity] rows: which version is
 * currently approved, overall lifecycle [status], and (while executing) the selected step. The actual
 * plan content lives in [PlanVersionEntity.snapshotJson], never here — mirrors how
 * [com.aiopenpocket.agent.core.plan.model.AgentPlan]'s own kdoc separates the two.
 *
 * FK CASCADE to [ConversationEntity]: deleting a conversation deletes every [AgentPlanEntity] scoped
 * to it, which in turn cascades to every [PlanVersionEntity]/[PlanStepStateEntity]/[PlanEvidenceEntity]/
 * [PlanEventEntity] row referencing THIS plan's [id] (each of those declares its own `onDelete =
 * CASCADE` FK back to this table) — the "archive/delete conversation cascades according to explicit FK
 * policy" contract, mirroring [com.aiopenpocket.agent.core.memory.entity.MessageEntity]'s existing
 * cascade-on-[ConversationEntity]-delete pattern. [agentId] deliberately has NO foreign key: it can
 * name either a Room-backed [com.aiopenpocket.agent.core.agent.UserAgentEntity] row OR a preset id that
 * only ever exists in the read-only `MiniAgentCatalog` Kotlin list (see [AgentPlan.agentId]'s own kdoc)
 * — an FK against `user_agents` would wrongly reject every preset-agent plan.
 *
 * Only [PlanRepository] ever writes [status]/[approvedVersion] — always as the output of
 * [com.aiopenpocket.agent.core.plan.validation.PlanStateMachine.transition]/`.approveVersion`, never a
 * hand-picked value — mirroring [com.aiopenpocket.agent.core.hook.AgentHookEntity]'s "one repository
 * owns every write" convention.
 */
@Entity(
  tableName = "agent_plans",
  foreignKeys = [
    ForeignKey(
      entity = ConversationEntity::class,
      parentColumns = ["id"],
      childColumns = ["conversationId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index("conversationId", "status"),
    Index("agentId", "status"),
  ],
)
data class AgentPlanEntity(
  @PrimaryKey val id: String,
  val agentId: String?,
  val conversationId: String,
  val horizon: String,
  val status: String,
  val approvedVersion: Int?,
  val activeStepId: String?,
  val createdAt: Long,
  val updatedAt: Long,
) {
  fun toDomain(): AgentPlan = AgentPlan(
    id = id,
    agentId = agentId,
    conversationId = conversationId,
    horizon = PlanHorizon.valueOf(horizon),
    status = PlanStatus.valueOf(status),
    approvedVersion = approvedVersion,
    activeStepId = activeStepId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  companion object {
    fun fromDomain(plan: AgentPlan): AgentPlanEntity = AgentPlanEntity(
      id = plan.id,
      agentId = plan.agentId,
      conversationId = plan.conversationId,
      horizon = plan.horizon.name,
      status = plan.status.name,
      approvedVersion = plan.approvedVersion,
      activeStepId = plan.activeStepId,
      createdAt = plan.createdAt,
      updatedAt = plan.updatedAt,
    )
  }
}
