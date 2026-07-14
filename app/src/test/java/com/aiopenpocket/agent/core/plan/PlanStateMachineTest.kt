package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.validation.PlanCommand
import com.aiopenpocket.agent.core.plan.validation.PlanStateMachine
import com.aiopenpocket.agent.core.plan.validation.PlanStepCommand
import com.aiopenpocket.agent.core.plan.validation.PlanTransitionResult
import com.aiopenpocket.agent.core.plan.validation.PlanVersionResult
import com.aiopenpocket.agent.core.plan.validation.StepTransitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanStateMachine] is a pure function set — no Robolectric, no I/O, no coroutines needed. Covers
 * the whole-plan [PlanStatus] graph, the per-step [StepStatus] graph, the evidence gate on
 * [PlanStatus.COMPLETED], and [PlanVersion] approval/revision immutability, per the design spec
 * (`2026-07-12-structured-plan-engine-design.md` sections 4/5/11) and the Task 1 brief: approve
 * freezes the version; a material revision on an approved plan yields a new pending version and
 * never mutates the approved one; a required criterion cannot complete a plan without evidence;
 * `PlanRunOutcome.PARTIAL` is not a `PlanStatus`; illegal transitions are rejected, never thrown.
 */
class PlanStateMachineTest {

  // --- fixtures --------------------------------------------------------------------------------

  private fun criterion(id: String, required: Boolean = true) = PlanCriterion(
    id = id,
    description = "criterion $id",
    verifier = CriterionVerifier(type = VerifierType.TOOL_RESULT),
    required = required,
  )

  private fun evidence(criterionId: String?, id: String = "ev-$criterionId") = PlanEvidence(
    id = id,
    planId = "plan-1",
    version = 1,
    criterionId = criterionId,
    stepId = null,
    source = EvidenceSource.TOOL_RESULT,
    reference = "trace-1",
    summary = "done",
    createdAt = 0L,
  )

  private fun step(id: String, dependsOn: Set<String> = emptySet(), expectedTools: Set<String> = emptySet()) = PlanStep(
    id = id,
    title = "step $id",
    instruction = "do $id",
    dependsOn = dependsOn,
    expectedTools = expectedTools,
    schedule = null,
    completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun planVersion(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = emptyList(),
    criteria: List<PlanCriterion> = emptyList(),
    approvedAt: Long? = null,
    createdBy: PlanAuthor = PlanAuthor.AGENT,
  ) = PlanVersion(
    planId = planId,
    version = version,
    objective = "objective",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = steps,
    criteria = criteria,
    createdBy = createdBy,
    approvedAt = approvedAt,
  )

  // --- whole-plan lifecycle: legal transitions --------------------------------------------------

  @Test
  fun `a validated proposal moves DRAFT to AWAITING_APPROVAL`() {
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.AWAITING_APPROVAL),
      PlanStateMachine.transition(PlanStatus.DRAFT, PlanCommand.ProposalValidated),
    )
  }

  @Test
  fun `missing context sends a draft to NEEDS_INFO, and supplying it returns to DRAFT`() {
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.NEEDS_INFO),
      PlanStateMachine.transition(PlanStatus.DRAFT, PlanCommand.NeedsInfo("what's your monthly budget?")),
    )
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.DRAFT),
      PlanStateMachine.transition(PlanStatus.NEEDS_INFO, PlanCommand.InfoProvided),
    )
  }

  @Test
  fun `requesting changes on a pending proposal returns it to DRAFT`() {
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.DRAFT),
      PlanStateMachine.transition(PlanStatus.AWAITING_APPROVAL, PlanCommand.RequestChanges("lower the weekly budget")),
    )
  }

  @Test
  fun `approving a pending proposal activates the plan`() {
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.ACTIVE),
      PlanStateMachine.transition(PlanStatus.AWAITING_APPROVAL, PlanCommand.Approve),
    )
  }

  @Test
  fun `pause then resume round-trips between ACTIVE and PAUSED`() {
    assertEquals(PlanTransitionResult.Allowed(PlanStatus.PAUSED), PlanStateMachine.transition(PlanStatus.ACTIVE, PlanCommand.Pause))
    assertEquals(PlanTransitionResult.Allowed(PlanStatus.ACTIVE), PlanStateMachine.transition(PlanStatus.PAUSED, PlanCommand.Resume))
  }

  @Test
  fun `a material change on an active plan requires a fresh proposal before it can be approved again`() {
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.NEEDS_REVISION),
      PlanStateMachine.transition(PlanStatus.ACTIVE, PlanCommand.MaterialChangeDetected("tool removed from agent scope")),
    )
    // The bridging edge back out of NEEDS_REVISION reuses ProposalValidated - a re-drafted plan is
    // validated exactly the same way a brand new one is, it just started from NEEDS_REVISION instead
    // of DRAFT (design spec section 4's "ACTIVE -> material revision -> AWAITING_APPROVAL" collapses
    // to these two concrete edges once DRAFT/NEEDS_REVISION are modeled as distinct PlanStatus values).
    assertEquals(
      PlanTransitionResult.Allowed(PlanStatus.AWAITING_APPROVAL),
      PlanStateMachine.transition(PlanStatus.NEEDS_REVISION, PlanCommand.ProposalValidated),
    )
  }

  @Test
  fun `cancel is legal from every non-terminal status`() {
    val nonTerminal = listOf(
      PlanStatus.DRAFT, PlanStatus.NEEDS_INFO, PlanStatus.AWAITING_APPROVAL,
      PlanStatus.ACTIVE, PlanStatus.PAUSED, PlanStatus.NEEDS_REVISION,
    )
    for (status in nonTerminal) {
      assertEquals(
        "expected Cancel to be legal from $status",
        PlanTransitionResult.Allowed(PlanStatus.CANCELLED),
        PlanStateMachine.transition(status, PlanCommand.Cancel),
      )
    }
  }

  @Test
  fun `fail is legal from ACTIVE, PAUSED and NEEDS_REVISION`() {
    for (status in listOf(PlanStatus.ACTIVE, PlanStatus.PAUSED, PlanStatus.NEEDS_REVISION)) {
      assertEquals(
        "expected Fail to be legal from $status",
        PlanTransitionResult.Allowed(PlanStatus.FAILED),
        PlanStateMachine.transition(status, PlanCommand.Fail("unrecoverable runtime error")),
      )
    }
  }

  // --- whole-plan lifecycle: illegal transitions rejected, never thrown --------------------------

  @Test
  fun `illegal transitions are rejected rather than thrown`() {
    // PlanStateMachine never throws - see its kdoc. This is the typed-rejection equivalent of the
    // brief's "assertFailsWith { transition(COMPLETED, Resume) }" case.
    val result = PlanStateMachine.transition(PlanStatus.COMPLETED, PlanCommand.Resume)

    assertTrue(result is PlanTransitionResult.Rejected)
    val rejected = result as PlanTransitionResult.Rejected
    assertEquals(PlanStatus.COMPLETED, rejected.error.from)
    assertEquals(PlanCommand.Resume, rejected.error.command)
  }

  @Test
  fun `approve is only legal from AWAITING_APPROVAL`() {
    assertTrue(PlanStateMachine.transition(PlanStatus.DRAFT, PlanCommand.Approve) is PlanTransitionResult.Rejected)
    assertTrue(PlanStateMachine.transition(PlanStatus.ACTIVE, PlanCommand.Approve) is PlanTransitionResult.Rejected)
    assertTrue(PlanStateMachine.transition(PlanStatus.NEEDS_REVISION, PlanCommand.Approve) is PlanTransitionResult.Rejected)
  }

  @Test
  fun `terminal plan statuses reject every command, for either horizon`() {
    // Design spec decision 2: one unified PlanStatus model serves SINGLE_RUN and PROGRAM alike - there
    // is no horizon-specific terminal status or escape hatch out of COMPLETED-CANCELLED-FAILED.
    val terminal = listOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)
    val everyCommand = listOf(
      PlanCommand.ProposalValidated,
      PlanCommand.NeedsInfo("q"),
      PlanCommand.InfoProvided,
      PlanCommand.RequestChanges("f"),
      PlanCommand.Approve,
      PlanCommand.Pause,
      PlanCommand.Resume,
      PlanCommand.MaterialChangeDetected("r"),
      PlanCommand.Complete,
      PlanCommand.Fail("r"),
      PlanCommand.Cancel,
    )
    for (status in terminal) {
      for (command in everyCommand) {
        assertTrue(
          "expected $status to reject $command",
          PlanStateMachine.transition(status, command) is PlanTransitionResult.Rejected,
        )
      }
    }
  }

  // --- PlanRunOutcome vs PlanStatus ---------------------------------------------------------------

  @Test
  fun `PARTIAL is a PlanRunOutcome, not a PlanStatus`() {
    assertTrue(PlanRunOutcome.entries.any { it.name == "PARTIAL" })
    assertFalse(PlanStatus.entries.any { it.name == "PARTIAL" })
  }

  // --- version immutability - approval freezes, revision never mutates ---------------------------

  @Test
  fun `approving a version stamps approvedAt`() {
    val draft = planVersion(approvedAt = null)

    val result = PlanStateMachine.approveVersion(draft, approvedAt = 1_000L)

    assertEquals(PlanVersionResult.Allowed(draft.copy(approvedAt = 1_000L)), result)
  }

  @Test
  fun `approving an already-approved version is rejected - approval never re-freezes it`() {
    val approved = planVersion(approvedAt = 500L)

    val result = PlanStateMachine.approveVersion(approved, approvedAt = 999L)

    assertTrue(result is PlanVersionResult.Rejected)
    assertEquals(approved, (result as PlanVersionResult.Rejected).version) // unchanged: still 500L.
  }

  @Test
  fun `a material revision on an approved plan yields a new pending version and never mutates the approved one`() {
    val approvedSteps = listOf(step("s1"))
    val approved = planVersion(version = 1, steps = approvedSteps, approvedAt = 1_000L)
    val draft = planVersion(version = 2, steps = approvedSteps + step("s2"), approvedAt = null)

    val result = PlanStateMachine.proposeRevision(approved, draft)

    assertEquals(PlanVersionResult.Allowed(draft), result)
    // The approved version object itself is untouched: still version 1, still approved, still its
    // original step list - a material revision never edits it in place.
    assertEquals(1, approved.version)
    assertEquals(1_000L, approved.approvedAt)
    assertEquals(approvedSteps, approved.steps)
  }

  @Test
  fun `a revision that reuses the approved version number is rejected`() {
    val approved = planVersion(version = 1, approvedAt = 1_000L)
    val badDraft = planVersion(version = 1, approvedAt = null) // should be 2

    assertTrue(PlanStateMachine.proposeRevision(approved, badDraft) is PlanVersionResult.Rejected)
  }

  @Test
  fun `a revision that is already pre-approved is rejected`() {
    val approved = planVersion(version = 1, approvedAt = 1_000L)
    val badDraft = planVersion(version = 2, approvedAt = 2_000L) // must start unapproved

    assertTrue(PlanStateMachine.proposeRevision(approved, badDraft) is PlanVersionResult.Rejected)
  }

  @Test
  fun `a revision for a different plan id is rejected`() {
    val approved = planVersion(planId = "plan-1", version = 1, approvedAt = 1_000L)
    val badDraft = planVersion(planId = "plan-2", version = 2, approvedAt = null)

    assertTrue(PlanStateMachine.proposeRevision(approved, badDraft) is PlanVersionResult.Rejected)
  }

  // --- evidence-gated completion -------------------------------------------------------------------

  @Test
  fun `a required criterion without evidence blocks completion`() {
    val criteria = listOf(criterion("c1", required = true))

    val result = PlanStateMachine.tryComplete(PlanStatus.ACTIVE, criteria, evidence = emptyList())

    assertTrue(result is PlanTransitionResult.Rejected)
  }

  @Test
  fun `a required criterion with matching evidence allows completion`() {
    val criteria = listOf(criterion("c1", required = true))

    val result = PlanStateMachine.tryComplete(PlanStatus.ACTIVE, criteria, evidence = listOf(evidence("c1")))

    assertEquals(PlanTransitionResult.Allowed(PlanStatus.COMPLETED), result)
  }

  @Test
  fun `a non-required criterion without evidence does not block completion`() {
    val criteria = listOf(criterion("c1", required = false))

    val result = PlanStateMachine.tryComplete(PlanStatus.ACTIVE, criteria, evidence = emptyList())

    assertEquals(PlanTransitionResult.Allowed(PlanStatus.COMPLETED), result)
  }

  @Test
  fun `unsatisfiedRequiredCriteria reports exactly the required criteria missing evidence`() {
    val criteria = listOf(
      criterion("required-missing", required = true),
      criterion("required-proven", required = true),
      criterion("optional", required = false),
    )
    val evidenceList = listOf(evidence("required-proven"))

    val unsatisfied = PlanStateMachine.unsatisfiedRequiredCriteria(criteria, evidenceList)

    assertEquals(listOf(criterion("required-missing")), unsatisfied)
  }

  @Test
  fun `both SINGLE_RUN and PROGRAM plans reach COMPLETED through the same evidence-gated transition`() {
    // The state machine itself is horizon-agnostic: PlanHorizon only shapes what PlanRunner (Task 10)
    // decides to *do* with due-runnable steps, never which PlanStatus values or edges are legal.
    val criteria = listOf(criterion("c1", required = true))
    val provenEvidence = listOf(evidence("c1"))

    for (horizon in PlanHorizon.entries) {
      assertEquals(
        "horizon=$horizon",
        PlanTransitionResult.Allowed(PlanStatus.COMPLETED),
        PlanStateMachine.tryComplete(PlanStatus.ACTIVE, criteria, provenEvidence),
      )
    }
  }

  // --- per-step lifecycle ---------------------------------------------------------------------

  @Test
  fun `a step becomes ready once its dependencies are satisfied`() {
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.READY),
      PlanStateMachine.transitionStep(StepStatus.PENDING, PlanStepCommand.DependenciesSatisfied),
    )
  }

  @Test
  fun `starting a ready step moves it to executing`() {
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.EXECUTING),
      PlanStateMachine.transitionStep(StepStatus.READY, PlanStepCommand.Start),
    )
  }

  @Test
  fun `finishing execution always routes to verifying, never straight to a terminal status`() {
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.VERIFYING),
      PlanStateMachine.transitionStep(StepStatus.EXECUTING, PlanStepCommand.Finish),
    )
    assertTrue(PlanStateMachine.transitionStep(StepStatus.EXECUTING, PlanStepCommand.Prove) is StepTransitionResult.Rejected)
    assertTrue(PlanStateMachine.transitionStep(StepStatus.EXECUTING, PlanStepCommand.FailStep("boom")) is StepTransitionResult.Rejected)
  }

  @Test
  fun `verifying can prove, retry, block or fail the step`() {
    assertEquals(StepTransitionResult.Allowed(StepStatus.COMPLETED), PlanStateMachine.transitionStep(StepStatus.VERIFYING, PlanStepCommand.Prove))
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.READY),
      PlanStateMachine.transitionStep(StepStatus.VERIFYING, PlanStepCommand.Retry("transient tool error")),
    )
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.BLOCKED),
      PlanStateMachine.transitionStep(StepStatus.VERIFYING, PlanStepCommand.Block("needs user approval")),
    )
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.FAILED),
      PlanStateMachine.transitionStep(StepStatus.VERIFYING, PlanStepCommand.FailStep("retries exhausted")),
    )
  }

  @Test
  fun `a failed step is not step-terminal - manual retry re-arms it`() {
    assertEquals(
      StepTransitionResult.Allowed(StepStatus.READY),
      PlanStateMachine.transitionStep(StepStatus.FAILED, PlanStepCommand.Retry("manual retry")),
    )
  }

  @Test
  fun `a blocked step resumes once unblocked, or is skipped outright`() {
    assertEquals(StepTransitionResult.Allowed(StepStatus.READY), PlanStateMachine.transitionStep(StepStatus.BLOCKED, PlanStepCommand.Unblock))
    assertEquals(StepTransitionResult.Allowed(StepStatus.SKIPPED), PlanStateMachine.transitionStep(StepStatus.BLOCKED, PlanStepCommand.Skip))
  }

  @Test
  fun `completed and skipped steps are step-terminal`() {
    val everyStepCommand = listOf(
      PlanStepCommand.DependenciesSatisfied,
      PlanStepCommand.Start,
      PlanStepCommand.Finish,
      PlanStepCommand.Prove,
      PlanStepCommand.Retry("r"),
      PlanStepCommand.Block("b"),
      PlanStepCommand.FailStep("f"),
      PlanStepCommand.Unblock,
      PlanStepCommand.Skip,
    )
    for (status in listOf(StepStatus.COMPLETED, StepStatus.SKIPPED)) {
      for (command in everyStepCommand) {
        assertTrue(
          "expected $status to reject $command",
          PlanStateMachine.transitionStep(status, command) is StepTransitionResult.Rejected,
        )
      }
    }
  }
}
