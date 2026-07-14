package com.aiopenpocket.agent.core.plan.model

// @trace structured-plan-engine-907066

import kotlinx.serialization.Serializable

/**
 * One immutable, versioned snapshot of an [AgentPlan]'s actual content (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 5) — the "frozen plan content"
 * `plan_versions.snapshotJson` persists whole (Task 3). Material changes (objective/constraints/
 * criteria, step add/remove/reorder, expectedTools, schedule, completionRule — see
 * `PlanChangeClassifier`, Task 2) never mutate an existing [PlanVersion]; they always produce a
 * new one with `version = this.version + 1` (plan's Global Constraints: "Approved PlanVersion
 * immutable. Material revision always creates a new pending version").
 *
 * [approvedAt] is null for a draft/pending version and set exactly once, by
 * `PlanStateMachine.approveVersion` — after that this specific [version] number is a frozen
 * historical record; nothing re-assigns [approvedAt] on the same version again, and a later
 * revision always allocates a new, higher [version] instead of touching this one
 * (`PlanStateMachine.proposeRevision`).
 */
@Serializable
data class PlanVersion(
  val planId: String,
  val version: Int,
  val objective: String,
  val assumptions: List<String>,
  val constraints: List<String>,
  val steps: List<PlanStep>,
  val criteria: List<PlanCriterion>,
  val createdBy: PlanAuthor,
  val approvedAt: Long?,
)
