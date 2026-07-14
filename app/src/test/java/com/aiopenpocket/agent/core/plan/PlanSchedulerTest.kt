package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.schedule.HeartbeatScheduler
import com.aiopenpocket.agent.core.plan.schedule.PlanScheduler
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
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
 * [PlanScheduler] (Task 11 of the Structured Plan Engine). The pure "what runs next, and when"
 * selection ([PlanScheduler.selectNextDue]) is tested directly against hand-built [PlanSnapshot]
 * fixtures — mirrors [PlanExecutorTest]'s own direct calls to `PlanExecutor.isRunnable`/
 * `resolveToolScope` (no Room needed, `internal` is module-visible to this test source set).
 * [PlanScheduler.sync]/[PlanScheduler.cancel] are round-tripped through a REAL in-memory Room
 * [PlanRepository]/`HeartbeatDao` (mirrors [PlanRunnerTest]'s own setup) with a
 * [RecordingHeartbeatScheduler] standing in for real WorkManager (mirrors [PlanApprovalTest]'s own
 * `RecordingPlanScheduleSync`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanSchedulerTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var heartbeatScheduler: RecordingHeartbeatScheduler
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    heartbeatScheduler = RecordingHeartbeatScheduler()
  }

  @After
  fun tearDown() = db.close()

  // --- fakes ------------------------------------------------------------------------------------

  /** Records every schedule/cancel call instead of touching WorkManager — mirrors [PlanApprovalTest]'s `RecordingPlanScheduleSync`. */
  private class RecordingHeartbeatScheduler : HeartbeatScheduler {
    val scheduled = mutableListOf<HeartbeatEntity>()
    val cancelled = mutableListOf<String>()
    override suspend fun schedule(entity: HeartbeatEntity) {
      scheduled += entity
    }
    override suspend fun cancel(heartbeatId: String) {
      cancelled += heartbeatId
    }
  }

  // --- fixtures (mirrors PlanRunnerTest/PlanApprovalTest's own builders) --------------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(
    id: String = "plan-1",
    conversationId: String = "conv-1",
    agentId: String? = "agent-1",
    horizon: PlanHorizon = PlanHorizon.PROGRAM,
    status: PlanStatus = PlanStatus.DRAFT,
    approvedVersion: Int? = null,
  ) = AgentPlan(
    id = id, agentId = agentId, conversationId = conversationId, horizon = horizon,
    status = status, approvedVersion = approvedVersion, activeStepId = null,
    createdAt = 1_000L, updatedAt = 1_000L,
  )

  private fun step(
    id: String = "step-1",
    dependsOn: Set<String> = emptySet(),
    schedule: PlanSchedule? = null,
  ) = PlanStep(
    id = id, title = "Title $id", instruction = "Instruction $id",
    dependsOn = dependsOn, expectedTools = emptySet(), schedule = schedule,
    completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = listOf(step()),
  ) = PlanVersion(
    planId = planId, version = version, objective = "Objective", assumptions = emptyList(),
    constraints = emptyList(), steps = steps, criteria = emptyList(), createdBy = PlanAuthor.AGENT, approvedAt = null,
  )

  private fun stepState(
    stepId: String,
    status: StepStatus,
    nextDueAt: Long? = null,
    planId: String = "plan-1",
    version: Int = 1,
    attempts: Int = 0,
  ) = PlanStepState(
    planId = planId, version = version, stepId = stepId, status = status,
    attempts = attempts, nextDueAt = nextDueAt, startedAt = null, completedAt = null,
  )

  /** Hand-built [PlanSnapshot] — no Room — for the PURE [PlanScheduler.selectNextDue] tests. */
  private fun snapshot(
    steps: List<PlanStep>,
    stepStates: List<PlanStepState> = emptyList(),
    planId: String = "plan-1",
    version: Int = 1,
    status: PlanStatus = PlanStatus.ACTIVE,
  ) = PlanSnapshot(
    plan = plan(id = planId, status = status, approvedVersion = version),
    version = version(planId = planId, version = version, steps = steps),
    stepStates = stepStates,
    evidence = emptyList(),
  )

  /** Real repository round-trip: DRAFT -> AWAITING_APPROVAL -> ACTIVE, approvedVersion=1. */
  private suspend fun approvedPlan(steps: List<PlanStep> = listOf(step()), planId: String = "plan-1"): String {
    insertConversation()
    repository.saveProposal(plan(id = planId), version(planId = planId, steps = steps))
    repository.approve(planId, 1)
    return planId
  }

  private fun scheduler() = PlanScheduler(repository, db.heartbeatDao(), heartbeatScheduler) { now }

  // --- pure selection: PlanScheduler.selectNextDue ------------------------------------------------

  @Test
  fun `a step with no schedule is always due right now`() {
    val snap = snapshot(steps = listOf(step(id = "step-1", schedule = null)))

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals("step-1", result?.step?.id)
    assertEquals(now, result?.dueAt)
  }

  @Test
  fun `a step whose dependsOn is not yet satisfied is excluded from selection`() {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snap = snapshot(
      steps = listOf(step(id = "step-1"), dependent),
      stepStates = listOf(stepState("step-1", StepStatus.PENDING)),
    )

    val result = PlanScheduler.selectNextDue(snap, now)

    // step-1 has no schedule (always due); step-2 stays excluded until step-1 completes.
    assertEquals("step-1", result?.step?.id)
  }

  @Test
  fun `a step becomes selectable once its dependency completes`() {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snap = snapshot(
      steps = listOf(step(id = "step-1"), dependent),
      stepStates = listOf(stepState("step-1", StepStatus.COMPLETED)),
    )

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals("step-2", result?.step?.id)
  }

  @Test
  fun `the earliest-due step wins, regardless of declared order`() {
    val later = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = 120))
    val sooner = step(id = "step-2", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))
    val snap = snapshot(
      steps = listOf(later, sooner),
      stepStates = listOf(
        stepState("step-1", StepStatus.READY, nextDueAt = now + 120 * 60_000L),
        stepState("step-2", StepStatus.READY, nextDueAt = now + 60 * 60_000L),
      ),
    )

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals("step-2", result?.step?.id)
    assertEquals(now + 60 * 60_000L, result?.dueAt)
  }

  @Test
  fun `a step with no progress row yet computes its due time from a ONE_TIME schedule's delayMinutes`() {
    val scheduled = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = 30))
    val snap = snapshot(steps = listOf(scheduled))

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals(now + 30 * 60_000L, result?.dueAt)
  }

  @Test
  fun `a PERIODIC step with no explicit delayMinutes falls back to intervalMinutes for its first due time`() {
    val scheduled = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 45))
    val snap = snapshot(steps = listOf(scheduled))

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals(now + 45 * 60_000L, result?.dueAt)
  }

  @Test
  fun `a persisted nextDueAt is used verbatim instead of recomputing from the schedule`() {
    val scheduled = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))
    val snap = snapshot(
      steps = listOf(scheduled),
      stepStates = listOf(stepState("step-1", StepStatus.READY, nextDueAt = 999_999L)),
    )

    val result = PlanScheduler.selectNextDue(snap, now)

    assertEquals(999_999L, result?.dueAt) // NOT now + 60min — the persisted value wins.
  }

  @Test
  fun `a completed step leaves nothing selectable`() {
    val snap = snapshot(
      steps = listOf(step(id = "step-1")),
      stepStates = listOf(stepState("step-1", StepStatus.COMPLETED)),
    )

    assertNull(PlanScheduler.selectNextDue(snap, now))
  }

  @Test
  fun `a blocked step is excluded from selection`() {
    val snap = snapshot(
      steps = listOf(step(id = "step-1")),
      stepStates = listOf(stepState("step-1", StepStatus.BLOCKED)),
    )

    assertNull(PlanScheduler.selectNextDue(snap, now))
  }

  // --- sync(): arming/reusing/cancelling real heartbeats -------------------------------------------

  @Test
  fun `sync on an unknown plan id is a harmless no-op`() = runTest {
    scheduler().sync("does-not-exist")

    assertTrue(heartbeatScheduler.scheduled.isEmpty())
    assertTrue(heartbeatScheduler.cancelled.isEmpty())
  }

  @Test
  fun `sync arms a typed PLAN_STEP heartbeat for an ACTIVE plan's next due step`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))

    scheduler().sync(planId)

    val row = db.heartbeatDao().listForPlan(planId).single()
    assertEquals(HeartbeatTargetType.PLAN_STEP.name, row.targetType)
    assertEquals(planId, row.planId)
    assertEquals(1, row.planVersion)
    assertEquals("step-1", row.planStepId)
    assertEquals("scheduled", row.status)
    assertEquals(now, row.fireAt)
    assertEquals(1, heartbeatScheduler.scheduled.size)
  }

  @Test
  fun `sync is idempotent - a second call with nothing changed reuses the same row instead of duplicating it`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))
    val s = scheduler()

    s.sync(planId)
    val firstId = db.heartbeatDao().listForPlan(planId).single().id
    s.sync(planId)
    val rows = db.heartbeatDao().listForPlan(planId)

    assertEquals(1, rows.size)
    assertEquals(firstId, rows.single().id)
    assertEquals(2, heartbeatScheduler.scheduled.size) // re-armed twice, never duplicated
    assertTrue(heartbeatScheduler.cancelled.isEmpty())
  }

  @Test
  fun `sync cancels the armed heartbeat once the plan is paused`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))
    val s = scheduler()
    s.sync(planId)
    val armedId = db.heartbeatDao().listForPlan(planId).single().id

    repository.pause(planId)
    s.sync(planId)

    assertTrue(db.heartbeatDao().listForPlan(planId).none { it.status == "scheduled" })
    assertTrue(heartbeatScheduler.cancelled.contains(armedId))
  }

  @Test
  fun `sync cancels a stale superseded-version row and arms a fresh one for the new version's step`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))
    val s = scheduler()
    s.sync(planId)
    val staleId = db.heartbeatDao().listForPlan(planId).single().id

    // Material revision to a DIFFERENT step, approved as version 2 — plan is ACTIVE again afterward.
    repository.saveProposal(
      plan(id = planId, status = PlanStatus.ACTIVE, approvedVersion = 1),
      version(planId = planId, version = 2, steps = listOf(step(id = "step-2"))),
    )
    repository.approve(planId, 2)

    s.sync(planId)

    assertTrue("stale version-1 row must be cancelled", heartbeatScheduler.cancelled.contains(staleId))
    val active = db.heartbeatDao().listForPlan(planId).single { it.status == "scheduled" }
    assertEquals(2, active.planVersion)
    assertEquals("step-2", active.planStepId)
  }

  @Test
  fun `sync cancels everything once every step is finished`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))
    val s = scheduler()
    s.sync(planId)
    val armedId = db.heartbeatDao().listForPlan(planId).single().id
    repository.updateStepState(stepState("step-1", StepStatus.COMPLETED, planId = planId))

    s.sync(planId)

    assertTrue(heartbeatScheduler.cancelled.contains(armedId))
    assertTrue(db.heartbeatDao().listForPlan(planId).none { it.status == "scheduled" })
  }

  @Test
  fun `cancel tears down every heartbeat currently targeting the plan`() = runTest {
    val planId = approvedPlan(steps = listOf(step(id = "step-1")))
    val s = scheduler()
    s.sync(planId)
    val armedId = db.heartbeatDao().listForPlan(planId).single().id

    s.cancel(planId)

    assertTrue(heartbeatScheduler.cancelled.contains(armedId))
    assertTrue(db.heartbeatDao().listForPlan(planId).none { it.status == "scheduled" })
  }

  @Test
  fun `sync persists a durable nextDueAt so a later call does not restart the countdown`() = runTest {
    val planId = approvedPlan(
      steps = listOf(step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = 30))),
    )
    val s = scheduler()

    s.sync(planId)
    val firstDueAt = db.heartbeatDao().listForPlan(planId).single().fireAt

    now += 5 * 60_000L // time passes; a later sync call must NOT recompute "now + 30min" again
    s.sync(planId)
    val secondDueAt = db.heartbeatDao().listForPlan(planId).single().fireAt

    assertEquals(firstDueAt, secondDueAt)
  }
}
