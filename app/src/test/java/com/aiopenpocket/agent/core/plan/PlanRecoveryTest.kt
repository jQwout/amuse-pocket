package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.plan.execution.PlanRecovery
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlanRecovery] (Task 14) round-tripped through a REAL (in-memory) Room [PlanRepository] — mirrors
 * [PlanRepositoryTest]/[PlanApprovalTest]'s own setup — with a [RecordingPlanScheduleSync] standing
 * in for the WorkManager-backed `com.aiopenpocket.agent.core.plan.schedule.PlanScheduler` (same
 * [PlanScheduleSync] seam [PlanApprovalTest] already fakes for [com.aiopenpocket.agent.core.plan.execution.PlanCommands]).
 * [PlanRecovery] never calls an LLM/tool executor at all (see its own kdoc "Where this runs"), so
 * there is no `PlanExecutor`/`PlanStepRunner` fake to wire here — only [PlanRepository] and
 * [PlanScheduleSync].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRecoveryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var scheduler: RecordingPlanScheduleSync
  private lateinit var recovery: PlanRecovery
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    scheduler = RecordingPlanScheduleSync()
    recovery = PlanRecovery(repository, scheduler) { now }
  }

  @After
  fun tearDown() = db.close()

  /** Records every [sync]/[cancel] call instead of touching WorkManager — mirrors [PlanApprovalTest]'s own fake. */
  private class RecordingPlanScheduleSync : PlanScheduleSync {
    val synced = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override suspend fun sync(planId: String) {
      synced += planId
    }
    override suspend fun cancel(planId: String) {
      cancelled += planId
    }
  }

  // --- fixtures (mirrors PlanRepositoryTest's own) ---------------------------------------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(
    id: String = "plan-1",
    conversationId: String = "conv-1",
    status: PlanStatus = PlanStatus.DRAFT,
    approvedVersion: Int? = null,
  ) = AgentPlan(
    id = id,
    agentId = "agent-1",
    conversationId = conversationId,
    horizon = PlanHorizon.SINGLE_RUN,
    status = status,
    approvedVersion = approvedVersion,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  /** A step that COULD side-effect ([expectedTools] non-empty) — the realistic "sent SMS / wrote a memory fact" case this whole task exists for. */
  private fun step(id: String = "step-1", dependsOn: Set<String> = emptySet()) = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = dependsOn,
    expectedTools = setOf("reminder_tool"),
    schedule = null,
    completionRule = CompletionRule(type = CompletionRuleType.EVIDENCE_PRESENT),
  )

  private fun criterion(id: String = "criterion-1", required: Boolean = true) = PlanCriterion(
    id = id,
    description = "Description $id",
    verifier = CriterionVerifier(type = VerifierType.TOOL_RESULT),
    required = required,
  )

  private fun version(
    planId: String = "plan-1",
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = listOf(criterion()),
  ) = PlanVersion(
    planId = planId,
    version = 1,
    objective = "Objective",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  private fun stepState(
    planId: String = "plan-1",
    stepId: String = "step-1",
    status: StepStatus,
    attempts: Int = 1,
    startedAt: Long? = 5_000L,
    completedAt: Long? = null,
    nextDueAt: Long? = null,
  ) = PlanStepState(
    planId = planId,
    version = 1,
    stepId = stepId,
    status = status,
    attempts = attempts,
    nextDueAt = nextDueAt,
    startedAt = startedAt,
    completedAt = completedAt,
  )

  private fun evidence(
    id: String,
    planId: String = "plan-1",
    stepId: String? = "step-1",
    criterionId: String? = "criterion-1",
    createdAt: Long,
  ) = PlanEvidence(
    id = id,
    planId = planId,
    version = 1,
    criterionId = criterionId,
    stepId = stepId,
    source = EvidenceSource.TOOL_RESULT,
    reference = "tool-trace-$id",
    summary = "Summary $id",
    createdAt = createdAt,
  )

  /** Builds an ACTIVE plan (approved version 1) with [steps]/[criteria], then stamps [stepState] for "step-1". */
  private suspend fun activePlanWithStuckStep(
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = listOf(criterion()),
    stuckStatus: StepStatus,
  ) {
    insertConversation()
    repository.saveProposal(plan(), version(steps = steps, criteria = criteria))
    repository.approve("plan-1", 1)
    repository.updateStepState(stepState(status = stuckStatus))
  }

  // --- Task 14 required cases -------------------------------------------------------------------------

  @Test
  fun `EXECUTING step with fresh evidence is completed, never re-run`() = runTest {
    activePlanWithStuckStep(stuckStatus = StepStatus.EXECUTING)
    // Evidence created AFTER the crashed attempt's own startedAt (5_000L) — proof the side effect
    // already happened and was durably captured before the process died.
    repository.addEvidence(evidence(id = "ev-1", createdAt = 6_000L))
    now = 20_000L

    recovery.recover()

    val snapshot = repository.snapshot("plan-1")!!
    val state = snapshot.stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.COMPLETED, state.status)
    assertEquals(20_000L, state.completedAt)
    assertEquals(5_000L, state.startedAt) // preserved, not restarted
    assertEquals(1, state.attempts) // preserved, not incremented — this is NOT a new attempt

    val recoveryEvents = repository.getEvents("plan-1").filter { it.stepId == "step-1" }
    val verified = recoveryEvents.single { it.type == PlanEventType.STEP_VERIFIED }
    assertEquals("true", stringField(verified.payload, "recovered"))

    assertEquals(listOf("plan-1"), scheduler.synced)
  }

  @Test
  fun `EXECUTING step with no evidence is blocked for review, never auto-completed or re-executed`() = runTest {
    activePlanWithStuckStep(stuckStatus = StepStatus.EXECUTING)
    now = 20_000L

    recovery.recover()

    val snapshot = repository.snapshot("plan-1")!!
    val state = snapshot.stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
    assertNull(state.completedAt)
    assertEquals(5_000L, state.startedAt) // preserved

    val recoveryEvents = repository.getEvents("plan-1").filter { it.stepId == "step-1" }
    val blocked = recoveryEvents.single { it.type == PlanEventType.STEP_BLOCKED }
    assertEquals("true", stringField(blocked.payload, "recovered"))
    assertTrue(
      "expected a manual-review reason, was: ${stringField(blocked.payload, "reason")}",
      stringField(blocked.payload, "reason").orEmpty().contains("manual review", ignoreCase = true),
    )

    assertEquals(listOf("plan-1"), scheduler.synced)
  }

  @Test
  fun `recovery is idempotent - a second pass changes nothing and syncs nothing again`() = runTest {
    activePlanWithStuckStep(stuckStatus = StepStatus.EXECUTING)
    now = 20_000L

    recovery.recover()
    val afterFirst = repository.snapshot("plan-1")!!
    val eventsAfterFirst = repository.getEvents("plan-1")

    now = 30_000L
    recovery.recover()
    val afterSecond = repository.snapshot("plan-1")!!
    val eventsAfterSecond = repository.getEvents("plan-1")

    assertEquals(afterFirst.stepStates, afterSecond.stepStates)
    assertEquals(eventsAfterFirst.size, eventsAfterSecond.size)
    assertEquals(eventsAfterFirst.map { it.id }, eventsAfterSecond.map { it.id })
    // Only synced once — the second pass found nothing EXECUTING/VERIFYING left to repair.
    assertEquals(listOf("plan-1"), scheduler.synced)
  }

  @Test
  fun `a cleanly-terminal plan is untouched by recovery`() = runTest {
    activePlanWithStuckStep(stuckStatus = StepStatus.EXECUTING)
    repository.cancel("plan-1")
    now = 20_000L

    recovery.recover()

    val snapshot = repository.snapshot("plan-1")!!
    assertEquals(PlanStatus.CANCELLED, snapshot.plan.status)
    // Untouched, even though the row still LOOKS like an in-flight crash — a terminal plan is
    // excluded at the source (PlanRepository.getNonTerminalPlans), never inspected per-step at all.
    assertEquals(StepStatus.EXECUTING, snapshot.stepStates.single { it.stepId == "step-1" }.status)
    assertTrue(repository.getEvents("plan-1").isEmpty())
    assertTrue(scheduler.synced.isEmpty())
  }

  @Test
  fun `a plan with only READY steps is untouched by recovery`() = runTest {
    activePlanWithStuckStep(stuckStatus = StepStatus.READY)
    now = 20_000L

    recovery.recover()

    val snapshot = repository.snapshot("plan-1")!!
    assertEquals(StepStatus.READY, snapshot.stepStates.single { it.stepId == "step-1" }.status)
    assertTrue(repository.getEvents("plan-1").isEmpty())
    assertTrue(scheduler.synced.isEmpty())
  }

  private fun stringField(payload: JsonObject, key: String): String? = (payload[key] as? JsonPrimitive)?.content
}
