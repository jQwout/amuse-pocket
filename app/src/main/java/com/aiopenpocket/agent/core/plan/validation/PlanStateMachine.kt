package com.aiopenpocket.agent.core.plan.validation

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus

/**
 * One event a caller (`PlanRepository`/`PlanPlanner`/`PlanRunner`/`PlanVerifier` — never the LLM
 * directly, and never a UI writing [PlanStatus] by hand) feeds into [PlanStateMachine.transition]
 * to move a whole-plan [PlanStatus] forward. Bare signals are `data object`s; commands whose
 * legality/audit trail needs a reason or feedback string carry one.
 *
 * [Complete] is deliberately payload-less: the evidence gate that decides whether reaching
 * [PlanStatus.COMPLETED] is actually legal lives in [PlanStateMachine.tryComplete], the only
 * supported way to issue this particular command (see its kdoc).
 */
sealed interface PlanCommand {
  /**
   * A validated plan proposal now exists (the planner's `submit_plan` succeeded). Legal from
   * [PlanStatus.DRAFT] (a brand-new plan) and from [PlanStatus.NEEDS_REVISION] (a re-drafted one
   * after [MaterialChangeDetected]) — both land on [PlanStatus.AWAITING_APPROVAL].
   */
  data object ProposalValidated : PlanCommand

  /** The planner could not produce a valid proposal without more context (`request_plan_information`). Legal from [PlanStatus.DRAFT] only. */
  data class NeedsInfo(val question: String) : PlanCommand

  /** The information [NeedsInfo] asked for was supplied; back to drafting. Legal from [PlanStatus.NEEDS_INFO] only. */
  data object InfoProvided : PlanCommand

  /** The user asked for changes instead of approving (`PlanCommands.requestChanges`). Legal from [PlanStatus.AWAITING_APPROVAL] only. */
  data class RequestChanges(val feedback: String) : PlanCommand

  /** The user approved the pending version — a typed repository command, never a magic chat message (plan's Global Constraints). Legal from [PlanStatus.AWAITING_APPROVAL] only. Freezing the approved [PlanVersion] itself is [PlanStateMachine.approveVersion], a separate call the repository makes alongside this one. */
  data object Approve : PlanCommand

  /** The user paused an active plan. Legal from [PlanStatus.ACTIVE] only. */
  data object Pause : PlanCommand

  /** The user resumed a paused plan. Legal from [PlanStatus.PAUSED] only. */
  data object Resume : PlanCommand

  /**
   * The executor/verifier/hook engine detected that the approved version itself can no longer run
   * as-is (e.g. a tool left the agent's scope — design spec section 16) and a new version must be
   * drafted before anything else can happen. Legal from [PlanStatus.ACTIVE]/[PlanStatus.PAUSED].
   * The follow-up [ProposalValidated] (once the new draft exists) is what actually reaches
   * [PlanStatus.AWAITING_APPROVAL] — this command only flags the need.
   */
  data class MaterialChangeDetected(val reason: String) : PlanCommand

  /**
   * All required criteria are satisfied. Legal from [PlanStatus.ACTIVE] only — but [transition]
   * alone does not verify that claim; use [PlanStateMachine.tryComplete], which checks
   * [PlanStateMachine.unsatisfiedRequiredCriteria] before ever constructing this command.
   */
  data object Complete : PlanCommand

  /** The whole plan could not continue (not one step failing — see [StepStatus.FAILED]/[PlanStepCommand.FailStep] for that). Legal from [PlanStatus.ACTIVE]/[PlanStatus.PAUSED]/[PlanStatus.NEEDS_REVISION]. */
  data class Fail(val reason: String) : PlanCommand

  /** The user cancelled the plan outright. Legal from every non-terminal [PlanStatus]. */
  data object Cancel : PlanCommand
}

/** Why [PlanStateMachine.transition] (or [PlanStateMachine.tryComplete]) refused a [PlanCommand] — a value, never thrown. See [PlanStateMachine] kdoc for why. */
data class InvalidPlanTransition(
  val from: PlanStatus,
  val command: PlanCommand,
  val reason: String,
)

/** Outcome of [PlanStateMachine.transition]/[PlanStateMachine.tryComplete]: either the new [PlanStatus], or a typed, non-thrown rejection. */
sealed interface PlanTransitionResult {
  data class Allowed(val status: PlanStatus) : PlanTransitionResult
  data class Rejected(val error: InvalidPlanTransition) : PlanTransitionResult
}

/** Outcome of a [PlanVersion]-level operation ([PlanStateMachine.approveVersion]/[PlanStateMachine.proposeRevision]): either the resulting [PlanVersion], or a typed, non-thrown rejection. */
sealed interface PlanVersionResult {
  data class Allowed(val version: PlanVersion) : PlanVersionResult
  data class Rejected(val version: PlanVersion, val reason: String) : PlanVersionResult
}

/**
 * One event a caller feeds into [PlanStateMachine.transitionStep] to move a single [StepStatus]
 * forward. This graph is independent of [PlanCommand]/[PlanStatus] — many steps advance inside one
 * [PlanStatus.ACTIVE] plan run, each on its own [StepStatus].
 */
sealed interface PlanStepCommand {
  /** This step's [com.aiopenpocket.agent.core.plan.model.PlanStep.dependsOn] are all [StepStatus.COMPLETED]/[StepStatus.SKIPPED]. Legal from [StepStatus.PENDING] only. */
  data object DependenciesSatisfied : PlanStepCommand

  /** The runner atomically claimed this step and is about to execute it (increments `PlanStepState.attempts`). Legal from [StepStatus.READY] only. */
  data object Start : PlanStepCommand

  /** Execution finished (successfully or not) and the result is ready for `PlanVerifier`. Legal from [StepStatus.EXECUTING] only — every execution outcome routes through verification, never straight to [StepStatus.FAILED]. */
  data object Finish : PlanStepCommand

  /** The verifier proved the step's [com.aiopenpocket.agent.core.plan.model.PlanStep.completionRule]. Legal from [StepStatus.VERIFYING] only. */
  data object Prove : PlanStepCommand

  /** The step failed but may run again within budget (`PlanRunBudget`, Task 7, decides whether to actually issue this). Legal from [StepStatus.VERIFYING] or [StepStatus.FAILED]. */
  data class Retry(val reason: String) : PlanStepCommand

  /** The step cannot proceed without approval/revision (a hook's `REQUIRE_APPROVAL`/`DENY` on `BEFORE_PLAN_STEP`, or the verifier finding the plan itself invalid). Legal from [StepStatus.READY] or [StepStatus.VERIFYING]. */
  data class Block(val reason: String) : PlanStepCommand

  /** Retries are exhausted; this step will not run again without a manual [Retry]. Legal from [StepStatus.VERIFYING] only. */
  data class FailStep(val reason: String) : PlanStepCommand

  /** A blocked step's cause was resolved (approval granted, revision approved). Legal from [StepStatus.BLOCKED] only. */
  data object Unblock : PlanStepCommand

  /** This step will never run (its dependency chain was abandoned, or the plan was restructured/cancelled around it). Legal from [StepStatus.PENDING], [StepStatus.READY] or [StepStatus.BLOCKED]. */
  data object Skip : PlanStepCommand
}

/** Why [PlanStateMachine.transitionStep] refused a [PlanStepCommand] — a value, never thrown. */
data class InvalidStepTransition(
  val from: StepStatus,
  val command: PlanStepCommand,
  val reason: String,
)

/** Outcome of [PlanStateMachine.transitionStep]: either the new [StepStatus], or a typed, non-thrown rejection. */
sealed interface StepTransitionResult {
  data class Allowed(val status: StepStatus) : StepTransitionResult
  data class Rejected(val error: InvalidStepTransition) : StepTransitionResult
}

/**
 * Pure transition rules for the Structured Plan Engine (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 4; plan's Global Constraints). No I/O, no
 * Android, no coroutines, no randomness, no wall-clock reads — every function here is a total,
 * deterministic mapping from (current state, command/data) to a result. Given the same inputs it
 * always returns the same output, and it NEVER throws: an illegal transition is reported as a
 * [PlanTransitionResult.Rejected]/[StepTransitionResult.Rejected]/[PlanVersionResult.Rejected]
 * value, exactly like [com.aiopenpocket.agent.core.policy.ExecutionPolicy.evaluate] and
 * `AgentValidator.validate` never throw either — callers branch on the result, they don't need a
 * try/catch around domain logic.
 *
 * Two independent graphs live here:
 * - [transition] over [PlanStatus] — the whole-plan lifecycle (propose → pending → approved →
 *   active/paused ⇄ each other → completed/failed/cancelled), driven by [PlanCommand].
 * - [transitionStep] over [StepStatus] — one step's own execute/verify cycle within an active
 *   plan run, driven by [PlanStepCommand]. Many steps advance through this graph independently
 *   while the whole plan sits in a single [PlanStatus.ACTIVE].
 *
 * Plus two families of smaller, related pure helpers:
 * - [approveVersion]/[proposeRevision] — [PlanVersion]-level operations enforcing that an approved
 *   version is frozen (plan's Global Constraints: "approved PlanVersion is IMMUTABLE") and that a
 *   material revision always allocates a new version number rather than mutating the old one.
 * - [tryComplete]/[unsatisfiedRequiredCriteria] — the evidence gate: a required
 *   [PlanCriterion] cannot be satisfied without a persisted [PlanEvidence] entry referencing it
 *   (plan's Global Constraints), enforced here rather than trusted to every future caller.
 *
 * Scope note: design spec section 4's prose diagram also mentions a plan going to `NEEDS_INFO`
 * while a step is executing ("EXECUTING ├─ missing input → NEEDS_INFO"). That diagram deliberately
 * mixes [PlanStatus] and [StepStatus] names in one illustration; the concrete per-field state lists
 * in section 5 have no such cross-level edge. Task 1 models a step that needs input as
 * [PlanStepCommand.Block] (the step blocks; the plan stays [PlanStatus.ACTIVE]) rather than
 * inventing an ACTIVE → NEEDS_INFO whole-plan edge the brief's own test list never asks for — a
 * later task can add it explicitly once product behavior for that case is pinned down.
 */
object PlanStateMachine {

  // --- whole-plan status graph ---------------------------------------------------------------

  /**
   * The single entry point for advancing a whole-plan [PlanStatus]. Returns
   * [PlanTransitionResult.Rejected] for any (state, command) pair not explicitly wired below —
   * including every command against a terminal status ([PlanStatus.COMPLETED],
   * [PlanStatus.CANCELLED], [PlanStatus.FAILED] have no legal outgoing transition at all).
   */
  fun transition(current: PlanStatus, command: PlanCommand): PlanTransitionResult {
    val next = legalNextPlanStatus(current, command)
    return if (next != null) {
      PlanTransitionResult.Allowed(next)
    } else {
      PlanTransitionResult.Rejected(
        InvalidPlanTransition(
          from = current,
          command = command,
          reason = "$command is not a legal command from $current",
        ),
      )
    }
  }

  /**
   * The only supported way to issue [PlanCommand.Complete]: refuses to even construct the command
   * unless every required criterion already has persisted evidence (plan's Global Constraints — a
   * required criterion cannot be satisfied without persisted [PlanEvidence]), then delegates to
   * [transition] for the ordinary status-graph check (still [PlanStatus.ACTIVE] only).
   */
  fun tryComplete(
    current: PlanStatus,
    criteria: List<PlanCriterion>,
    evidence: List<PlanEvidence>,
    steps: List<PlanStep>,
    stepStates: List<PlanStepState>,
  ): PlanTransitionResult {
    val unsatisfied = unsatisfiedRequiredCriteria(criteria, evidence)
    if (unsatisfied.isNotEmpty()) {
      return PlanTransitionResult.Rejected(
        InvalidPlanTransition(
          from = current,
          command = PlanCommand.Complete,
          reason = "Required criteria without persisted evidence: ${unsatisfied.joinToString { it.id }}",
        ),
      )
    }
    val unfinished = unfinishedSteps(steps, stepStates)
    if (unfinished.isNotEmpty()) {
      return PlanTransitionResult.Rejected(
        InvalidPlanTransition(
          from = current,
          command = PlanCommand.Complete,
          reason = "Steps still to run: ${unfinished.joinToString { it.id }}",
        ),
      )
    }
    return transition(current, PlanCommand.Complete)
  }

  /**
   * Every step in [steps] that is not yet [StepStatus.COMPLETED]/[StepStatus.SKIPPED] per
   * [stepStates] — a step with no state row at all counts as unfinished. Empty means the step gate
   * does not block [PlanStatus.COMPLETED].
   *
   * Без этого гейта план без критериев (или с уже закрытыми) объявлялся бы завершённым сразу после
   * ПЕРВОГО шага, и остальные шаги не выполнялись бы никогда: «все критерии доказаны» — условие
   * необходимое, но не достаточное.
   */
  fun unfinishedSteps(steps: List<PlanStep>, stepStates: List<PlanStepState>): List<PlanStep> {
    val statusByStepId = stepStates.associate { it.stepId to it.status }
    return steps.filter { statusByStepId[it.id] !in FINISHED_STEP_STATUSES }
  }

  /**
   * Every [PlanCriterion.required] criterion in [criteria] that [evidence] does not reference by
   * [PlanEvidence.criterionId]. Empty means the evidence gate does not block
   * [PlanStatus.COMPLETED] — [transition]'s own status-graph rules still apply on top of this.
   * Public (not just an implementation detail of [tryComplete]) because `PlanValidator`/UI
   * (later tasks) need the same "what's still missing" list to show a user, not just a yes/no.
   */
  fun unsatisfiedRequiredCriteria(criteria: List<PlanCriterion>, evidence: List<PlanEvidence>): List<PlanCriterion> {
    val provenCriterionIds = evidence.mapNotNullTo(mutableSetOf()) { it.criterionId }
    return criteria.filter { it.required && it.id !in provenCriterionIds }
  }

  private fun legalNextPlanStatus(current: PlanStatus, command: PlanCommand): PlanStatus? = when (current) {
    PlanStatus.DRAFT -> when (command) {
      is PlanCommand.ProposalValidated -> PlanStatus.AWAITING_APPROVAL
      is PlanCommand.NeedsInfo -> PlanStatus.NEEDS_INFO
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    PlanStatus.NEEDS_INFO -> when (command) {
      is PlanCommand.InfoProvided -> PlanStatus.DRAFT
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    PlanStatus.AWAITING_APPROVAL -> when (command) {
      is PlanCommand.Approve -> PlanStatus.ACTIVE
      is PlanCommand.RequestChanges -> PlanStatus.DRAFT
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    PlanStatus.ACTIVE -> when (command) {
      is PlanCommand.Pause -> PlanStatus.PAUSED
      is PlanCommand.MaterialChangeDetected -> PlanStatus.NEEDS_REVISION
      is PlanCommand.Complete -> PlanStatus.COMPLETED
      is PlanCommand.Fail -> PlanStatus.FAILED
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    PlanStatus.PAUSED -> when (command) {
      is PlanCommand.Resume -> PlanStatus.ACTIVE
      is PlanCommand.MaterialChangeDetected -> PlanStatus.NEEDS_REVISION
      is PlanCommand.Fail -> PlanStatus.FAILED
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    PlanStatus.NEEDS_REVISION -> when (command) {
      is PlanCommand.ProposalValidated -> PlanStatus.AWAITING_APPROVAL
      is PlanCommand.Fail -> PlanStatus.FAILED
      is PlanCommand.Cancel -> PlanStatus.CANCELLED
      else -> null
    }

    // Terminal: COMPLETED/CANCELLED/FAILED never have a legal outgoing transition.
    PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED -> null
  }

  // --- PlanVersion lifecycle (immutability / revisioning) ------------------------------------

  /**
   * Freezes [version] by stamping [approvedAt], refusing if it is already approved — approval
   * never re-freezes the same version number (plan's Global Constraints: approved [PlanVersion]
   * is immutable). The caller ([PlanCommand.Approve] handler / `PlanRepository.approve`, Task 3)
   * is expected to call this alongside `transition(AWAITING_APPROVAL, Approve)`; neither call
   * implies the other.
   */
  fun approveVersion(version: PlanVersion, approvedAt: Long): PlanVersionResult {
    if (version.approvedAt != null) {
      return PlanVersionResult.Rejected(
        version = version,
        reason = "Version ${version.version} of plan ${version.planId} is already approved (at ${version.approvedAt}); approval cannot re-freeze it.",
      )
    }
    return PlanVersionResult.Allowed(version.copy(approvedAt = approvedAt))
  }

  /**
   * Validates that [draft] is a legal material revision of the currently [approved] version:
   * same plan, exactly one version number higher, and not itself pre-approved. Never mutates
   * [approved] — Kotlin's `val`-only [PlanVersion] already makes that structurally impossible, but
   * this is the one place that also *rejects* a caller who tries to reuse/decrement a version
   * number instead of allocating a new one (plan's Global Constraints: "a material revision always
   * creates a NEW pending version").
   */
  fun proposeRevision(approved: PlanVersion, draft: PlanVersion): PlanVersionResult = when {
    draft.planId != approved.planId ->
      PlanVersionResult.Rejected(draft, "Revision planId '${draft.planId}' does not match approved plan '${approved.planId}'.")

    draft.version != approved.version + 1 ->
      PlanVersionResult.Rejected(draft, "Revision must be version ${approved.version + 1}, was ${draft.version}.")

    draft.approvedAt != null ->
      PlanVersionResult.Rejected(draft, "A newly proposed revision must start unapproved (approvedAt == null).")

    else -> PlanVersionResult.Allowed(draft)
  }

  // --- per-step status graph ------------------------------------------------------------------

  /**
   * The single entry point for advancing one [StepStatus]. Returns [StepTransitionResult.Rejected]
   * for any (state, command) pair not explicitly wired below — including every command against a
   * terminal step status ([StepStatus.COMPLETED]/[StepStatus.SKIPPED]).
   */
  fun transitionStep(current: StepStatus, command: PlanStepCommand): StepTransitionResult {
    val next = legalNextStepStatus(current, command)
    return if (next != null) {
      StepTransitionResult.Allowed(next)
    } else {
      StepTransitionResult.Rejected(
        InvalidStepTransition(
          from = current,
          command = command,
          reason = "$command is not a legal command from $current",
        ),
      )
    }
  }

  private fun legalNextStepStatus(current: StepStatus, command: PlanStepCommand): StepStatus? = when (current) {
    StepStatus.PENDING -> when (command) {
      is PlanStepCommand.DependenciesSatisfied -> StepStatus.READY
      is PlanStepCommand.Skip -> StepStatus.SKIPPED
      else -> null
    }

    StepStatus.READY -> when (command) {
      is PlanStepCommand.Start -> StepStatus.EXECUTING
      is PlanStepCommand.Block -> StepStatus.BLOCKED
      is PlanStepCommand.Skip -> StepStatus.SKIPPED
      else -> null
    }

    // Every execution outcome (success, tool error, exception) is handed to the verifier first —
    // there is no direct EXECUTING -> FAILED edge (design spec section 8's transaction order:
    // "EXECUTING → VERIFYING ... VERIFYING → COMPLETED / BLOCKED / FAILED / READY").
    StepStatus.EXECUTING -> when (command) {
      is PlanStepCommand.Finish -> StepStatus.VERIFYING
      else -> null
    }

    StepStatus.VERIFYING -> when (command) {
      is PlanStepCommand.Prove -> StepStatus.COMPLETED
      is PlanStepCommand.Retry -> StepStatus.READY
      is PlanStepCommand.Block -> StepStatus.BLOCKED
      is PlanStepCommand.FailStep -> StepStatus.FAILED
      else -> null
    }

    // Not terminal at the step level (unlike PlanStatus.FAILED): a manual retry re-arms it.
    StepStatus.FAILED -> when (command) {
      is PlanStepCommand.Retry -> StepStatus.READY
      else -> null
    }

    StepStatus.BLOCKED -> when (command) {
      is PlanStepCommand.Unblock -> StepStatus.READY
      is PlanStepCommand.Skip -> StepStatus.SKIPPED
      else -> null
    }

    // Terminal: COMPLETED/SKIPPED never have a legal outgoing transition.
    StepStatus.COMPLETED, StepStatus.SKIPPED -> null
  }

  /** «Шаг отработан» — та же пара статусов, что у PlanExecutor.isWholePlanComplete/PlanRunner. */
  private val FINISHED_STEP_STATUSES = setOf(StepStatus.COMPLETED, StepStatus.SKIPPED)
}
