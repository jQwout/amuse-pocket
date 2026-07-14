package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Room row for one append-only [PlanEvent] audit entry (`plan_events`) — what the plan detail
 * timeline (Task 12) renders and what the plan-hooks `ON_ERROR` non-recursion guard (design spec
 * section 12.2) relies on for a stable record of what already happened. [PlanRepository.appendEvent]
 * is the sole write path — this repository never synthesizes an event on its own initiative for
 * [PlanRepository.saveProposal]/`.approve`/`.requestRevision`/`.pause`/`.resume`/`.cancel`; see that
 * class's kdoc "What this class does NOT do" for why (Task 9 owns automatic lifecycle emission).
 *
 * [version]/[stepId] are nullable, matching [PlanEvent]'s own kdoc: null for whole-plan events that
 * predate any version or aren't step-scoped (e.g. [PlanEventType.CREATED]) — deliberately NOT a
 * foreign key against `plan_versions`, so an event can reference planId alone before any version row
 * exists (or after multiple accumulate) without SQLite's composite-FK NULL semantics being load-bearing
 * here.
 */
@Entity(
  tableName = "plan_events",
  foreignKeys = [
    ForeignKey(
      entity = AgentPlanEntity::class,
      parentColumns = ["id"],
      childColumns = ["planId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("planId", "createdAt")],
)
data class PlanEventEntity(
  @PrimaryKey val id: String,
  val planId: String,
  val version: Int?,
  val stepId: String?,
  val type: String,
  val payloadJson: String,
  val createdAt: Long,
) {
  fun toDomain(): PlanEvent = PlanEvent(
    id = id,
    planId = planId,
    version = version,
    stepId = stepId,
    type = PlanEventType.valueOf(type),
    payload = Json.decodeFromString<JsonObject>(payloadJson),
    createdAt = createdAt,
  )

  companion object {
    fun fromDomain(event: PlanEvent): PlanEventEntity = PlanEventEntity(
      id = event.id,
      planId = event.planId,
      version = event.version,
      stepId = event.stepId,
      type = event.type.name,
      payloadJson = Json.encodeToString<JsonObject>(event.payload),
      createdAt = event.createdAt,
    )
  }
}
