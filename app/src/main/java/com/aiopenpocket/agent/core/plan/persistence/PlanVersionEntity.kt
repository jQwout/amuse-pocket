package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.ForeignKey
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room row for one immutable [PlanVersion] snapshot (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 6: "`plan_versions.snapshotJson` хранит
 * immutable version целиком"). [snapshotJson] serializes the WHOLE [PlanVersion] — including this same
 * [planId]/[version]/[approvedAt] redundantly — so [toDomain] never has to stitch fields from two
 * places; [planId]/[version]/[approvedAt] are ALSO plain columns purely so SQL can query/index them (the
 * composite primary key, [PlanRepository]'s "already approved?" checks) without deserializing
 * [snapshotJson] first. Uses the plain top-level `kotlinx.serialization.json.Json` instance — the same
 * default (no `ignoreUnknownKeys`/custom config) [com.aiopenpocket.agent.core.hook.HookEngine] already
 * uses for its own opaque JSON columns.
 *
 * IMMUTABILITY (plan's Global Constraints: "approved PlanVersion is immutable"): [PlanRepository] never
 * writes a DIFFERENT [snapshotJson] for a (`planId`, `version`) pair that is already approved — the one
 * legal follow-up write against an already-approved row is [PlanRepository.approve] itself stamping
 * [approvedAt] from null to a value (still content-identical otherwise, since [PlanVersion.approvedAt]
 * is itself part of what gets re-serialized). A material revision always targets a NEW, strictly higher
 * `version` number instead of touching this row.
 */
@Entity(
  tableName = "plan_versions",
  primaryKeys = ["planId", "version"],
  foreignKeys = [
    ForeignKey(
      entity = AgentPlanEntity::class,
      parentColumns = ["id"],
      childColumns = ["planId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class PlanVersionEntity(
  val planId: String,
  val version: Int,
  val snapshotJson: String,
  val approvedAt: Long?,
) {
  fun toDomain(): PlanVersion = Json.decodeFromString<PlanVersion>(snapshotJson)

  companion object {
    fun fromDomain(version: PlanVersion): PlanVersionEntity = PlanVersionEntity(
      planId = version.planId,
      version = version.version,
      snapshotJson = Json.encodeToString(version),
      approvedAt = version.approvedAt,
    )
  }
}
