package com.aiopenpocket.agent.core.plan.model

// @trace structured-plan-engine-907066

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The persisted, whole-plan aggregate (design spec `2026-07-12-structured-plan-engine-design.md`
 * section 5) — mutable metadata that survives across [PlanVersion]s: which version is currently
 * approved, overall lifecycle [status], and (while executing) which step is presently selected.
 * The actual plan content (objective/assumptions/constraints/steps/criteria) lives in the
 * immutable [PlanVersion] that [approvedVersion] points to — never here.
 *
 * [status] is only ever assigned via `PlanStateMachine.transition`
 * (`com.aiopenpocket.agent.core.plan.validation.PlanStateMachine`) — no other writer should set it
 * directly, mirroring how [com.aiopenpocket.agent.core.agent.AgentDefinition.enabled] is the one
 * place that reads [com.aiopenpocket.agent.core.agent.AgentStatus] rather than a second field that
 * could disagree with it.
 *
 * [agentId] is nullable: a plan is always scoped to a [conversationId], but not every conversation
 * necessarily runs a custom/preset [com.aiopenpocket.agent.core.agent.AgentDefinition].
 */
@Serializable
data class AgentPlan(
  val id: String,
  val agentId: String?,
  val conversationId: String,
  val horizon: PlanHorizon,
  val status: PlanStatus,
  val approvedVersion: Int?,
  val activeStepId: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * When a [PlanStep] fires again. [ONE_TIME] steps carry [delayMinutes] (fire once, this many
 * minutes after becoming due); [PERIODIC] steps carry [intervalMinutes] (re-fire on that cadence).
 * Only meaningful for [PlanHorizon.PROGRAM] plans — `PlanValidator` (Task 2) rejects a scheduled
 * step on a [PlanHorizon.SINGLE_RUN] plan and enforces the minimum periodic interval.
 */
@Serializable
data class PlanSchedule(
  val kind: ScheduleKind,
  val delayMinutes: Long? = null,
  val intervalMinutes: Long? = null,
)

/**
 * One node in a [PlanVersion]'s step graph (design spec section 5). [dependsOn] are other
 * [PlanStep.id]s in the same version that must be [StepStatus.COMPLETED] (or [StepStatus.SKIPPED])
 * before this step becomes runnable — `PlanValidator` (Task 2) rejects cycles/missing references.
 *
 * [expectedTools] is this step's own contribution to the running tool scope; the actual set a
 * runner may call for this step is always
 * `agent.toolNames ∩ step.expectedTools ∩ ExecutionPolicy`
 * (plan's Global Constraints) — an empty set means this step must not call any tool at all.
 */
@Serializable
data class PlanStep(
  val id: String,
  val title: String,
  val instruction: String,
  val dependsOn: Set<String>,
  val expectedTools: Set<String>,
  val schedule: PlanSchedule?,
)

/**
 * How a [PlanCriterion] can be proven. [config] is interpreted by the matching verifier
 * implementation (`DeterministicVerifierRegistry`/`PlanVerifier`, Task 8) — e.g. which memory key
 * or tool name to look for — never free text the model can satisfy by simply asserting success.
 */
@Serializable
data class CriterionVerifier(
  val type: VerifierType,
  val config: JsonObject = JsonObject(emptyMap()),
)

/**
 * One condition an [AgentPlan] must prove to be allowed to reach [PlanStatus.COMPLETED] (design
 * spec section 5/11). Deliberately NOT tied to a single [PlanStep.id] — a [PlanEvidence] entry may
 * reference this criterion's [id] via [PlanEvidence.criterionId] regardless of which step produced
 * it. When [required] is true, `PlanStateMachine.tryComplete` refuses [PlanStatus.COMPLETED] unless
 * at least one [PlanEvidence] references this criterion (plan's Global Constraints: "a required
 * criterion cannot be satisfied without persisted PlanEvidence").
 */
@Serializable
data class PlanCriterion(
  val id: String,
  val description: String,
  val verifier: CriterionVerifier,
  val required: Boolean,
)
