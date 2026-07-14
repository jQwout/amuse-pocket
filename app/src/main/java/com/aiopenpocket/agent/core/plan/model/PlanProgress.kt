package com.aiopenpocket.agent.core.plan.model

// @trace structured-plan-engine-907066

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Runtime progress of one [PlanStep] within one specific [PlanVersion] (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 5) — normalized/updated independently of
 * the immutable [PlanVersion] it belongs to, so bumping [attempts] or [status] never requires
 * touching `plan_versions.snapshotJson` (Task 3). Identified by the ([planId], [version], [stepId])
 * triple so a superseded version's old progress rows are never confused with the currently
 * approved version's.
 */
@Serializable
data class PlanStepState(
  val planId: String,
  val version: Int,
  val stepId: String,
  val status: StepStatus,
  val attempts: Int,
  val nextDueAt: Long?,
  val startedAt: Long?,
  val completedAt: Long?,
)

/**
 * A persisted reference to a real, observed fact — a tool result, a memory row, a user's typed
 * confirmation, or (weakest) a model's own assessment (design spec section 5/11) — never free-text
 * model prose asserting success. [criterionId] and [stepId] are independently optional: one
 * [PlanEvidence] entry can support a whole-plan [PlanCriterion], document a single [PlanStep]'s own
 * completion, both, or (rarely) neither yet still be a persisted audit record.
 *
 * [reference] is a stable pointer into the source of truth (a tool-trace id, a memory key, an
 * event id) that a later verifier/audit can re-resolve, distinct from [summary]'s human-readable
 * text, which is not itself proof of anything.
 */
@Serializable
data class PlanEvidence(
  val id: String,
  val planId: String,
  val version: Int,
  val criterionId: String?,
  val stepId: String?,
  val source: EvidenceSource,
  val reference: String,
  val summary: String,
  val createdAt: Long,
)

/**
 * One append-only audit entry for a plan's lifecycle (`plan_events`, Task 3) — what the plan
 * detail timeline (Task 12) renders, and what the plan-hooks `ON_ERROR` non-recursion guard
 * (design spec section 12.2) relies on for a stable record of what already happened. [version]/
 * [stepId] are null for whole-plan events that predate any version or aren't step-scoped (e.g.
 * [PlanEventType.CREATED]).
 */
@Serializable
data class PlanEvent(
  val id: String,
  val planId: String,
  val version: Int?,
  val stepId: String?,
  val type: PlanEventType,
  val payload: JsonObject,
  val createdAt: Long,
)

/**
 * A read-model combining one [AgentPlan] with a [PlanVersion] and its progress/evidence — exactly
 * what `PlanRepository.snapshot`/`observe` (Task 3) hands to planner/executor/verifier/UI so none
 * of them has to separately join four tables itself. [version] is whichever [PlanVersion] the
 * caller asked for (typically the approved one; the pending one while awaiting approval) — this
 * type does not itself decide which; [stepStates]/[evidence] are scoped to that same
 * ([planId], [version]) pair.
 */
@Serializable
data class PlanSnapshot(
  val plan: AgentPlan,
  val version: PlanVersion,
  val stepStates: List<PlanStepState>,
  val evidence: List<PlanEvidence>,
)
