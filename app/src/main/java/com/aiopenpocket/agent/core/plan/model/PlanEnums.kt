package com.aiopenpocket.agent.core.plan.model

// @trace structured-plan-engine-907066

import kotlinx.serialization.Serializable

/**
 * How long an [AgentPlan] is expected to run (design spec
 * `2026-07-12-structured-plan-engine-design.md` sections 1, 12). [SINGLE_RUN] is a one-off task
 * that is expected to reach [PlanStatus.COMPLETED] once its steps/criteria are satisfied;
 * [PROGRAM] is a recurring/long-horizon plan whose steps can carry a [PlanSchedule] and resume via
 * typed heartbeat across many separate runs. Neither horizon ever loops forever in one run — see
 * [PlanRunOutcome.PARTIAL].
 */
@Serializable
enum class PlanHorizon { SINGLE_RUN, PROGRAM }

/**
 * Lifecycle status of a whole [AgentPlan] (design spec sections 4, 5). `PlanStateMachine`
 * (`validation/PlanStateMachine.kt`) is the only place allowed to compute the next value of this
 * field — nothing else should assign [AgentPlan.status] directly.
 *
 * Terminal values (no legal outgoing transition): [COMPLETED], [CANCELLED], [FAILED]. `PARTIAL` is
 * deliberately NOT a member here — a single run finishing early/incomplete is a [PlanRunOutcome],
 * not a status the whole plan itself can sit in (design spec section 4: "PARTIAL является
 * результатом конкретного run, но не обязано закрывать долгую программу").
 */
@Serializable
enum class PlanStatus {
  DRAFT,
  NEEDS_INFO,
  AWAITING_APPROVAL,
  ACTIVE,
  PAUSED,
  NEEDS_REVISION,
  COMPLETED,
  CANCELLED,
  FAILED,
}

/**
 * Per-step runtime status, tracked independently of [PlanStatus] in [PlanStepState] (design spec
 * section 5). Terminal values: [COMPLETED], [SKIPPED]. Unlike a plan-level [PlanStatus.FAILED],
 * a step sitting in [FAILED] can still be retried back to [READY] manually (design spec section
 * 16: "Step исчерпал retries: FAILED_STEP, затем manual retry/revision/cancel").
 */
@Serializable
enum class StepStatus { PENDING, READY, EXECUTING, VERIFYING, BLOCKED, COMPLETED, FAILED, SKIPPED }

/**
 * The outcome of one bounded `PlanRunner` run (a handful of due/runnable steps within a
 * `PlanRunBudget`) — distinct from [PlanStatus], which describes the whole plan, not one run.
 * [PARTIAL] is the normal outcome for an in-progress [PlanHorizon.PROGRAM]: this run's budget/due
 * steps were consumed but the plan itself stays [PlanStatus.ACTIVE], waiting for the next due
 * checkpoint (design spec section 4).
 */
@Serializable
enum class PlanRunOutcome { PARTIAL, COMPLETED, FAILED, CANCELLED }

/** Who authored a given [PlanVersion]'s content — a human edit via chat/UI, or the LLM planner. */
@Serializable
enum class PlanAuthor { USER, AGENT }

/**
 * What kind of record backs one [PlanEvidence] entry (design spec section 5). [MODEL_ASSESSMENT]
 * is the only source that is not a reference to an already-persisted/observed fact — the design
 * treats it as weak evidence, valid only for criteria explicitly configured with
 * [VerifierType.MODEL_ASSESSMENT], never as a substitute for a deterministic verifier.
 */
@Serializable
enum class EvidenceSource { TOOL_RESULT, MEMORY_FACT, USER_CONFIRMATION, MODEL_ASSESSMENT }

/** Audit-trail entry kind persisted to `plan_events` (Task 3) and read by the plan detail timeline (Task 12). */
@Serializable
enum class PlanEventType {
  CREATED,
  PROPOSED,
  APPROVED,
  REVISION_REQUESTED,
  STEP_STARTED,
  STEP_VERIFIED,
  STEP_BLOCKED,
  PLAN_PAUSED,
  PLAN_RESUMED,
  PLAN_COMPLETED,
  PLAN_CANCELLED,
  ERROR,
}

/**
 * Shape of a [PlanStep.schedule]: a single future firing, or a recurring interval. Only
 * meaningful for [PlanHorizon.PROGRAM] steps — `PlanValidator` (Task 2) rejects a scheduled step
 * on a [PlanHorizon.SINGLE_RUN] plan.
 */
@Serializable
enum class ScheduleKind { ONE_TIME, PERIODIC }

/**
 * How `PlanVerifier` (Task 8) decides a [PlanStep] itself is finished — interpreted by engine
 * code against [PlanStep.completionRule], never by a free-text model claim of "done".
 */
@Serializable
enum class CompletionRuleType { TOOL_SUCCEEDED, EVIDENCE_PRESENT, USER_CONFIRMED, MODEL_ASSESSMENT }

/**
 * How a [PlanCriterion.verifier] proves that criterion. Mirrors [EvidenceSource] one-for-one so a
 * criterion's required proof kind always corresponds to a real evidence kind.
 */
@Serializable
enum class VerifierType { TOOL_RESULT, MEMORY_FACT, USER_CONFIRMATION, MODEL_ASSESSMENT }
