package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.plan.execution.PlanCommands
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlanCommands] — the typed surface Task 5 puts in front of [PlanRepository] so chat's "Выполнить"
 * action (and future Plan UI actions — pause/resume/cancel/request changes) issue a repository
 * command instead of the Фаза A hack of simulating approval with the magic chat message
 * `"Одобряю план, выполни его по шагам."` (that constant — `TurnTexts.APPROVE_PLAN_MESSAGE` — and
 * `TurnManager.approvePlan` are both removed by this same task; see `TurnManagerTest`).
 *
 * Round-tripped through a real in-memory Room DB (mirrors [PlanRepositoryTest]'s own setup, subset
 * of its fixtures) because "no magic message is persisted for approval" needs a real `messages`
 * table to prove empty against, not a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanApprovalTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var scheduler: RecordingPlanScheduleSync
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var commands: PlanCommands
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    scheduler = RecordingPlanScheduleSync()
    hookRepository = AgentHookRepository(db.agentHookDao())
    commands = PlanCommands(repository, scheduler, hookRepository)
  }

  @After
  fun tearDown() = db.close()

  /** Records every [sync]/[cancel] call instead of touching WorkManager — see [PlanScheduleSync] kdoc. */
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

  // --- fixtures (a minimal subset of PlanRepositoryTest's own) --------------------------------

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

  private fun step(id: String = "step-1") = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = emptySet(),
    expectedTools = setOf("memory_tool"),
    schedule = null,  )

  private fun criterion(id: String = "criterion-1") = PlanCriterion(
    id = id,
    description = "Description $id",
    verifier = CriterionVerifier(type = VerifierType.MEMORY_FACT),
    required = true,
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    objective: String = "Objective v$version",
  ) = PlanVersion(
    planId = planId,
    version = version,
    objective = objective,
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = listOf(step()),
    criteria = listOf(criterion()),
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  // --- approve ----------------------------------------------------------------------------------

  @Test
  fun `approve routes to PlanRepository for the exact version and persists no conversation message`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version(version = 1))

    commands.approve("plan-1", 1)

    val snapshot = repository.snapshot("plan-1")!!
    assertEquals(PlanStatus.ACTIVE, snapshot.plan.status)
    assertEquals(1, snapshot.plan.approvedVersion)
    assertNotNull(snapshot.version.approvedAt)
    assertTrue(
      "approval must not insert a magic chat message into the conversation",
      db.messageDao().getAllOrdered("conv-1").isEmpty(),
    )
  }

  @Test
  fun `approve arms the schedule sync port for the approved plan`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version(version = 1))

    commands.approve("plan-1", 1)

    assertEquals(listOf("plan-1"), scheduler.synced)
  }

  @Test
  fun `approving a superseded version is rejected and leaves the approved row untouched`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(), version(version = 1, objective = "Objective v1"))
      commands.approve("plan-1", 1)
      // A material revision while ACTIVE: a new pending version 2 supersedes version 1 as "current".
      repository.saveProposal(
        plan(status = PlanStatus.ACTIVE, approvedVersion = 1),
        version(version = 2, objective = "Objective v2"),
      )
    }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { commands.approve("plan-1", 1) } // 1 is stale now; pending is 2
    }

    runBlocking {
      val snapshot = repository.snapshot("plan-1")!!
      assertEquals(1, snapshot.plan.approvedVersion) // unchanged by the rejected call
      assertEquals(PlanStatus.AWAITING_APPROVAL, snapshot.plan.status) // still awaiting v2, not re-approved
    }
    // Only the FIRST (successful) approve should have reached the scheduler; the rejected one must not.
    assertEquals(listOf("plan-1"), scheduler.synced)
  }

  // --- requestChanges -----------------------------------------------------------------------

  @Test
  fun `requestChanges sends the plan back to DRAFT and leaves the approved version untouched`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version(version = 1, objective = "Objective v1"))
    commands.approve("plan-1", 1)
    repository.saveProposal(
      plan(status = PlanStatus.ACTIVE, approvedVersion = 1),
      version(version = 2, objective = "Objective v2 revision"),
    )
    assertEquals(PlanStatus.AWAITING_APPROVAL, repository.snapshot("plan-1")!!.plan.status)

    commands.requestChanges("plan-1", 2, "уточни бюджет")

    assertEquals(PlanStatus.DRAFT, repository.snapshot("plan-1")!!.plan.status)
    val v1 = repository.getVersions("plan-1").single { it.version == 1 }
    assertEquals("Objective v1", v1.objective)
    assertNotNull("the originally approved version stays approved", v1.approvedAt)

    // The planner re-drafts after the feedback: saveProposal creates a NEW pending version — this
    // is what "request-revision creates a new pending version" means end to end; requestChanges
    // itself only flips status back to DRAFT (see PlanRepository.requestRevision kdoc).
    repository.saveProposal(
      plan(status = PlanStatus.DRAFT, approvedVersion = 1),
      version(version = 3, objective = "Objective v3 after feedback"),
    )

    val after = repository.snapshot("plan-1")!!
    assertEquals(PlanStatus.AWAITING_APPROVAL, after.plan.status)
    assertEquals(3, after.version.version)
    assertEquals(1, after.plan.approvedVersion)
    val v1After = repository.getVersions("plan-1").single { it.version == 1 }
    assertEquals("Objective v1", v1After.objective) // still untouched
  }

  @Test
  fun `requestChanges against a stale version is rejected before touching the repository`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(), version(version = 1))
      commands.approve("plan-1", 1)
      repository.saveProposal(plan(status = PlanStatus.ACTIVE, approvedVersion = 1), version(version = 2))
    }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { commands.requestChanges("plan-1", 1, "too late, already superseded") }
    }

    // Rejected before delegating to the repository: status is exactly as it was, still AWAITING_APPROVAL on v2.
    assertEquals(PlanStatus.AWAITING_APPROVAL, runBlocking { repository.snapshot("plan-1")!!.plan.status })
  }

  // --- pause/resume/cancel -------------------------------------------------------------------

  @Test
  fun `pause routes to PlanRepository and cancels the schedule sync`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version())
    commands.approve("plan-1", 1)

    commands.pause("plan-1")

    assertEquals(PlanStatus.PAUSED, repository.snapshot("plan-1")!!.plan.status)
    assertEquals(listOf("plan-1"), scheduler.cancelled)
  }

  @Test
  fun `resume routes to PlanRepository and re-arms the schedule sync`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version())
    commands.approve("plan-1", 1)
    commands.pause("plan-1")

    commands.resume("plan-1")

    assertEquals(PlanStatus.ACTIVE, repository.snapshot("plan-1")!!.plan.status)
    assertEquals(listOf("plan-1", "plan-1"), scheduler.synced) // approve, then resume — both arm it
  }

  @Test
  fun `cancel routes to PlanRepository and tears down the schedule sync`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version())
    commands.approve("plan-1", 1)

    commands.cancel("plan-1")

    assertEquals(PlanStatus.CANCELLED, repository.snapshot("plan-1")!!.plan.status)
    assertEquals(listOf("plan-1"), scheduler.cancelled)
  }
}
