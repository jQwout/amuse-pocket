package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.AgentPlanEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanEventEntity
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.persistence.PlanVersionEntity
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlanRepository] round-tripped through a real (in-memory) Room database (mirrors
 * [com.aiopenpocket.agent.core.hook.AgentHookRepositoryTest]/[com.aiopenpocket.agent.core.agent.UserAgentRepositoryTest]'s
 * own setup) — proves the required Task 3 contract: an approved [PlanVersion] is immutable, progress/
 * evidence/events round-trip, the serialized step/criteria columns survive a real SQLite write+read,
 * and the conversation FK cascade actually deletes a plan's whole tree.
 *
 * Every plan created here first needs a matching [ConversationEntity] row: [com.aiopenpocket.agent.core.plan.persistence.AgentPlanEntity]
 * declares a `NOT NULL` foreign key on `conversationId`, so [insertConversation] is called at the top
 * of every test that persists a plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
  }

  @After
  fun tearDown() = db.close()

  // --- fixtures -------------------------------------------------------------------------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(
    id: String = "plan-1",
    agentId: String? = "agent-1",
    conversationId: String = "conv-1",
    horizon: PlanHorizon = PlanHorizon.SINGLE_RUN,
    status: PlanStatus = PlanStatus.DRAFT,
    approvedVersion: Int? = null,
    activeStepId: String? = null,
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L,
  ) = AgentPlan(
    id = id,
    agentId = agentId,
    conversationId = conversationId,
    horizon = horizon,
    status = status,
    approvedVersion = approvedVersion,
    activeStepId = activeStepId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  private fun step(
    id: String = "step-1",
    dependsOn: Set<String> = emptySet(),
    expectedTools: Set<String> = setOf("memory_tool"),
    schedule: PlanSchedule? = null,
    completionRule: CompletionRule = CompletionRule(
      type = CompletionRuleType.TOOL_SUCCEEDED,
      config = JsonObject(mapOf("tool" to JsonPrimitive("memory_tool"))),
    ),
  ) = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = dependsOn,
    expectedTools = expectedTools,
    schedule = schedule,
    completionRule = completionRule,
  )

  private fun criterion(
    id: String = "criterion-1",
    required: Boolean = true,
    verifier: CriterionVerifier = CriterionVerifier(
      type = VerifierType.MEMORY_FACT,
      config = JsonObject(mapOf("key" to JsonPrimitive("budget.limit"))),
    ),
  ) = PlanCriterion(id = id, description = "Description $id", verifier = verifier, required = required)

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    objective: String = "Objective",
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = listOf(criterion()),
    approvedAt: Long? = null,
  ) = PlanVersion(
    planId = planId,
    version = version,
    objective = objective,
    assumptions = listOf("assumption-1"),
    constraints = listOf("constraint-1"),
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = approvedAt,
  )

  private fun stepState(
    planId: String = "plan-1",
    version: Int = 1,
    stepId: String = "step-1",
    status: StepStatus = StepStatus.PENDING,
    attempts: Int = 0,
    nextDueAt: Long? = null,
    startedAt: Long? = null,
    completedAt: Long? = null,
  ) = PlanStepState(
    planId = planId,
    version = version,
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
    version: Int = 1,
    criterionId: String? = "criterion-1",
    stepId: String? = "step-1",
    createdAt: Long = 2_000L,
  ) = PlanEvidence(
    id = id,
    planId = planId,
    version = version,
    criterionId = criterionId,
    stepId = stepId,
    source = EvidenceSource.TOOL_RESULT,
    reference = "tool-trace-$id",
    summary = "Summary $id",
    createdAt = createdAt,
  )

  private fun event(
    id: String,
    planId: String = "plan-1",
    type: PlanEventType = PlanEventType.CREATED,
    createdAt: Long = 500L,
  ) = PlanEvent(
    id = id,
    planId = planId,
    version = null,
    stepId = null,
    type = type,
    payload = JsonObject(emptyMap()),
    createdAt = createdAt,
  )

  // --- core Task 3 Step 1 required cases --------------------------------------------------------------

  // --- decode resilience (D): one undecodable row must not crash the whole read Flow ----------------

  @Test
  fun `observeByConversation skips a plan whose version row no longer decodes instead of crashing the whole flow (D)`() = runTest {
    insertConversation("c1")
    repository.saveProposal(plan(id = "good", conversationId = "c1"), version(planId = "good", objective = "Хороший план"))
    repository.saveProposal(plan(id = "bad", conversationId = "c1"), version(planId = "bad", objective = "Битый план"))
    // Schema drift with no Room migration (an enum rename, or a new required PlanVersion field): a
    // version row that no longer decodes. Before the fix this throws inside toSnapshot and the whole
    // Flow that ChatViewModel/PlanListViewModel collect crashes -> the screen crash-loops on every open.
    db.planDao().upsertVersion(PlanVersionEntity(planId = "bad", version = 1, snapshotJson = "{}", approvedAt = null))

    val plans = repository.observeByConversation("c1").first()

    assertEquals("the good plan still renders; the undecodable one is quarantined, not fatal", listOf("good"), plans.map { it.plan.id })
  }

  @Test
  fun `a version row with an unknown extra JSON key still decodes (forward-compatible read, not quarantined) (D)`() = runTest {
    insertConversation("c1")
    repository.saveProposal(plan(id = "p1", conversationId = "c1"), version(planId = "p1", objective = "План"))
    val validJson = db.planDao().getVersion("p1", 1)!!.snapshotJson
    // A row written by a NEWER app version that added a field this build doesn't know about. Strict
    // decode throws on the unknown key (so it would be quarantined and the plan would vanish); a
    // forward-compatible read must ignore the unknown key and keep the plan.
    val forwardCompatJson = validJson.replaceFirst("{", "{\"unknownFutureField\":123,")
    db.planDao().upsertVersion(PlanVersionEntity(planId = "p1", version = 1, snapshotJson = forwardCompatJson, approvedAt = null))

    val snapshot = repository.observeByConversation("c1").first().single()

    assertEquals("План", snapshot.version.objective)
  }

  @Test
  fun `observeEvents skips an event whose type enum no longer decodes instead of crashing (D-tail)`() = runTest {
    insertConversation("c1")
    repository.saveProposal(plan(id = "p1", conversationId = "c1"), version(planId = "p1"))
    db.planDao().insertEvent(PlanEventEntity(id = "e-good", planId = "p1", version = 1, stepId = null, type = PlanEventType.ERROR.name, payloadJson = "{}", createdAt = 1L))
    // A renamed/removed event type this build no longer knows: valueOf throws. Before quarantine this
    // crashes the whole observeEvents Flow that the plan-detail timeline collects.
    db.planDao().insertEvent(PlanEventEntity(id = "e-bad", planId = "p1", version = 1, stepId = null, type = "RENAMED_AWAY", payloadJson = "{}", createdAt = 2L))

    val events = repository.observeEvents("p1").first()

    assertEquals(listOf("e-good"), events.map { it.id })
  }

  @Test
  fun `getNonTerminalPlans skips a plan whose status enum no longer decodes instead of crashing (D-tail)`() = runTest {
    insertConversation("c1")
    repository.saveProposal(plan(id = "good", conversationId = "c1"), version(planId = "good"))
    // A plan row with a status this build no longer knows (renamed enum), still "non-terminal" by SQL,
    // so it reaches toDomain -> valueOf throws. The recovery sweep must skip it, not crash.
    db.planDao().upsertPlan(
      AgentPlanEntity(
        id = "bad", agentId = "agent-1", conversationId = "c1", horizon = "SINGLE_RUN",
        status = "RENAMED_STATUS", approvedVersion = null, activeStepId = null, createdAt = 1L, updatedAt = 1L,
      ),
    )

    val plans = repository.getNonTerminalPlans()

    assertTrue("the good plan survives", plans.any { it.id == "good" })
    assertTrue("the undecodable plan is quarantined, not fatal", plans.none { it.id == "bad" })
  }

  @Test
  fun `saveProposal moves a new plan to AWAITING_APPROVAL`() = runTest {
    insertConversation()
    val p = plan()
    val v = version()

    repository.saveProposal(p, v)

    assertEquals(PlanStatus.AWAITING_APPROVAL, repository.snapshot(p.id)!!.plan.status)
  }

  @Test
  fun `approve stamps approvedVersion and moves the plan to ACTIVE`() = runTest {
    insertConversation()
    val p = plan()
    val v = version()
    repository.saveProposal(p, v)

    repository.approve(p.id, v.version)

    val snapshot = repository.snapshot(p.id)!!
    assertEquals(v.version, snapshot.plan.approvedVersion)
    assertEquals(PlanStatus.ACTIVE, snapshot.plan.status)
    assertNotNull(snapshot.version.approvedAt)
  }

  @Test
  fun `addEvidence is visible in the plan's snapshot`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)
    val ev = evidence(id = "ev-1")

    repository.addEvidence(ev)

    assertEquals(ev.id, repository.snapshot(p.id)!!.evidence.single().id)
  }

  @Test
  fun `an approved version cannot be updated - saveProposal rejects reusing its version number`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(), version(objective = "Original objective"))
      repository.approve("plan-1", 1)
    }

    val ex = assertThrows(IllegalStateException::class.java) {
      runBlocking {
        // Same version number (1) as the already-approved row, different content.
        repository.saveProposal(plan(status = PlanStatus.ACTIVE, approvedVersion = 1), version(objective = "Tampered objective"))
      }
    }
    assertTrue("expected an 'already approved' message, was: ${ex.message}", ex.message.orEmpty().contains("already approved"))

    runBlocking {
      assertEquals("Original objective", repository.snapshot("plan-1")!!.version.objective)
    }
  }

  @Test
  fun `wrong pending version cannot be approved`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(), version())
    }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.approve("plan-1", 999) }
    }
  }

  @Test
  fun `a material revision creates a new version and leaves the old approved version active and untouched`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version(version = 1, objective = "Objective v1"))
    repository.approve(p.id, 1)

    // Propose a material revision (a materially different objective) while the plan is ACTIVE.
    repository.saveProposal(
      plan(status = PlanStatus.ACTIVE, approvedVersion = 1),
      version(version = 2, objective = "Objective v2 - materially different"),
    )

    val afterProposal = repository.snapshot(p.id)!!
    assertEquals(PlanStatus.AWAITING_APPROVAL, afterProposal.plan.status)
    assertEquals(1, afterProposal.plan.approvedVersion) // old approved version still active
    assertEquals(2, afterProposal.version.version) // snapshot now surfaces the pending revision for review

    val v1Row = repository.getVersions(p.id).single { it.version == 1 }
    assertEquals("Objective v1", v1Row.objective) // untouched
    assertNotNull(v1Row.approvedAt)

    repository.approve(p.id, 2)
    assertEquals(2, repository.snapshot(p.id)!!.plan.approvedVersion)
    val v1RowAfter = repository.getVersions(p.id).single { it.version == 1 }
    assertEquals("Objective v1", v1RowAfter.objective) // still untouched after the new version is approved
  }

  @Test
  fun `only one plan per conversation may be ACTIVE at a time`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(id = "plan-a"), version(planId = "plan-a"))
      repository.approve("plan-a", 1)
      repository.saveProposal(plan(id = "plan-b"), version(planId = "plan-b"))
    }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.approve("plan-b", 1) }
    }
  }

  @Test
  fun `approving a second plan in a DIFFERENT conversation is unaffected`() = runTest {
    insertConversation("conv-1")
    insertConversation("conv-2")
    repository.saveProposal(plan(id = "plan-a", conversationId = "conv-1"), version(planId = "plan-a"))
    repository.approve("plan-a", 1)
    repository.saveProposal(plan(id = "plan-b", conversationId = "conv-2"), version(planId = "plan-b"))

    repository.approve("plan-b", 1)

    assertEquals(PlanStatus.ACTIVE, repository.snapshot("plan-b")!!.plan.status)
  }

  @Test
  fun `deleting a conversation cascades to its plan and every child row`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)
    repository.updateStepState(stepState())
    repository.addEvidence(evidence(id = "ev-1"))
    repository.appendEvent(event(id = "evt-1"))

    db.conversationDao().delete("conv-1")

    assertNull(repository.snapshot(p.id))
    assertTrue(repository.getVersions(p.id).isEmpty())
    assertTrue(repository.getEvents(p.id).isEmpty())
  }

  // --- additional Task 3 TESTS section coverage ------------------------------------------------------

  @Test
  fun `progress round-trips through updateStepState`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)
    val state = stepState(status = StepStatus.EXECUTING, attempts = 2, startedAt = 5_000L)

    repository.updateStepState(state)

    assertEquals(state, repository.snapshot(p.id)!!.stepStates.single())
  }

  @Test
  fun `updateStepState upserts in place instead of accumulating duplicate rows`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)
    repository.updateStepState(stepState(status = StepStatus.READY, attempts = 0))

    repository.updateStepState(stepState(status = StepStatus.EXECUTING, attempts = 1))

    val states = repository.snapshot(p.id)!!.stepStates
    assertEquals(1, states.size)
    assertEquals(StepStatus.EXECUTING, states.single().status)
  }

  @Test
  fun `evidence appends and is queryable per plan and version`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)

    repository.addEvidence(evidence(id = "ev-1", criterionId = "criterion-1", stepId = null))
    repository.addEvidence(evidence(id = "ev-2", criterionId = null, stepId = "step-1"))

    val stored = repository.snapshot(p.id)!!.evidence
    assertEquals(setOf("ev-1", "ev-2"), stored.map { it.id }.toSet())
    assertTrue(stored.all { it.planId == p.id && it.version == 1 })
  }

  @Test
  fun `events append in order regardless of insertion order`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())

    repository.appendEvent(event(id = "evt-3", createdAt = 300))
    repository.appendEvent(event(id = "evt-1", createdAt = 100))
    repository.appendEvent(event(id = "evt-2", createdAt = 200))

    assertEquals(listOf("evt-1", "evt-2", "evt-3"), repository.getEvents(p.id).map { it.id })
  }

  @Test
  fun `observeEvents reflects appendEvent`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())

    repository.appendEvent(event(id = "evt-1", type = PlanEventType.CREATED))
    repository.appendEvent(event(id = "evt-2", type = PlanEventType.PROPOSED))

    val observed = repository.observeEvents(p.id).first()
    assertEquals(listOf("evt-1", "evt-2"), observed.map { it.id })
  }

  @Test
  fun `the serialized step and criteria columns round-trip exactly`() = runTest {
    insertConversation()
    val p = plan()
    val rich = version(
      steps = listOf(
        step(id = "collect", dependsOn = emptySet(), expectedTools = setOf("memory_tool", "srs_tool")),
        step(
          id = "verify",
          dependsOn = setOf("collect"),
          expectedTools = emptySet(),
          schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = 30),
          completionRule = CompletionRule(type = CompletionRuleType.USER_CONFIRMED),
        ),
        step(
          id = "recur",
          dependsOn = setOf("verify"),
          schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 120),
        ),
      ),
      criteria = listOf(
        criterion(id = "criterion-1", required = true),
        criterion(
          id = "criterion-2",
          required = false,
          verifier = CriterionVerifier(type = VerifierType.MODEL_ASSESSMENT),
        ),
      ),
    )

    repository.saveProposal(p, rich)

    assertEquals(rich, repository.snapshot(p.id)!!.version)
  }

  @Test
  fun `observeByConversation reacts to a plan's status changing`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())

    assertEquals(PlanStatus.AWAITING_APPROVAL, repository.observeByConversation("conv-1").first().single().plan.status)

    repository.approve(p.id, 1)

    assertEquals(PlanStatus.ACTIVE, repository.observeByConversation("conv-1").first().single().plan.status)
  }

  @Test
  fun `observeByAgent returns every plan for that agent, regardless of status`() = runTest {
    insertConversation("conv-1")
    insertConversation("conv-2")
    repository.saveProposal(plan(id = "plan-a", agentId = "agent-x", conversationId = "conv-1"), version(planId = "plan-a"))
    repository.saveProposal(plan(id = "plan-b", agentId = "agent-x", conversationId = "conv-2"), version(planId = "plan-b"))
    repository.saveProposal(plan(id = "plan-c", agentId = "agent-y", conversationId = "conv-2"), version(planId = "plan-c"))

    val forAgentX = repository.observeByAgent("agent-x").first()

    assertEquals(setOf("plan-a", "plan-b"), forAgentX.map { it.plan.id }.toSet())
  }

  @Test
  fun `pause then resume round-trips ACTIVE - PAUSED - ACTIVE`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())
    repository.approve(p.id, 1)

    repository.pause(p.id)
    assertEquals(PlanStatus.PAUSED, repository.snapshot(p.id)!!.plan.status)

    repository.resume(p.id)
    assertEquals(PlanStatus.ACTIVE, repository.snapshot(p.id)!!.plan.status)
  }

  @Test
  fun `requestRevision sends an AWAITING_APPROVAL plan back to DRAFT`() = runTest {
    insertConversation()
    val p = plan()
    repository.saveProposal(p, version())

    repository.requestRevision(p.id, "please add a deadline")

    assertEquals(PlanStatus.DRAFT, repository.snapshot(p.id)!!.plan.status)
  }

  @Test
  fun `cancel is legal from ACTIVE and is terminal`() {
    runBlocking {
      insertConversation()
      val p = plan()
      repository.saveProposal(p, version())
      repository.approve(p.id, 1)

      repository.cancel(p.id)
    }

    assertEquals(PlanStatus.CANCELLED, runBlocking { repository.snapshot("plan-1")!!.plan.status })

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.pause("plan-1") }
    }
  }

  @Test
  fun `pause is illegal from DRAFT`() {
    runBlocking {
      insertConversation()
      repository.saveProposal(plan(), version())
      repository.requestRevision("plan-1", "back to drawing board")
    }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.pause("plan-1") }
    }
  }
}
