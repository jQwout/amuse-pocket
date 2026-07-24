package com.aiopenpocket.ui.plan

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
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlanDetailViewModel] round-tripped through a real in-memory Room DB plus a real [PlanCommands]
 * (with a recording [PlanScheduleSync] instead of WorkManager) — mirrors
 * [com.aiopenpocket.agent.core.plan.PlanApprovalTest]'s own setup. Proves the combined
 * snapshot+events projection, criterion satisfaction from persisted evidence, and that
 * pause/resume/cancel go through [PlanCommands] (Task 5) rather than a second lifecycle mechanism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanDetailViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var scheduler: RecordingPlanScheduleSync
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var commands: PlanCommands
  private lateinit var vm: PlanDetailViewModel
  private var now = 10_000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      // Room по умолчанию исполняет запросы своих Flow на СВОИХ реальных executor'ах (процесс-глобальный
      // ArchTaskExecutor), за которыми виртуальные часы runTest не следят. Под нагрузкой ПОЛНОГО набора
      // этот общий пул голодает, и событийный Flow долетал бы до `first { events.size == 2 }` уже после
      // таймаута runTest — редкая флака, видимая только в полном прогоне, не в изоляции. Сажаем Room на
      // тот же тестовый диспетчер (как AgentsViewModelTest/AgentBuilderViewModelTest).
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    repository = PlanRepository(db, db.planDao()) { now }
    scheduler = RecordingPlanScheduleSync()
    hookRepository = AgentHookRepository(db.agentHookDao())
    commands = PlanCommands(repository, scheduler, hookRepository)
    vm = PlanDetailViewModel(repository, commands)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    db.close()
  }

  /** Records every sync/cancel call instead of touching WorkManager — mirrors PlanApprovalTest's own fake. */
  private class RecordingPlanScheduleSync : PlanScheduleSync {
    val synced = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override suspend fun sync(planId: String) { synced += planId }
    override suspend fun cancel(planId: String) { cancelled += planId }
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(id: String = "plan-1", conversationId: String = "conv-1") = AgentPlan(
    id = id,
    agentId = "agent-1",
    conversationId = conversationId,
    horizon = PlanHorizon.SINGLE_RUN,
    status = PlanStatus.DRAFT,
    approvedVersion = null,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  private fun step(id: String = "step-1", dependsOn: Set<String> = emptySet()) = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = dependsOn,
    expectedTools = emptySet(),
    schedule = null,  )

  private fun criterion(id: String = "criterion-1", required: Boolean = true) = PlanCriterion(
    id = id,
    description = "Description $id",
    verifier = CriterionVerifier(type = VerifierType.MEMORY_FACT),
    required = required,
  )

  private fun version(
    planId: String = "plan-1",
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = emptyList(),
  ) = PlanVersion(
    planId = planId,
    version = 1,
    objective = "Objective $planId",
    assumptions = listOf("assumption-1"),
    constraints = listOf("constraint-1"),
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  private fun evidence(id: String = "ev-1", criterionId: String? = null, planId: String = "plan-1") = PlanEvidence(
    id = id,
    planId = planId,
    version = 1,
    criterionId = criterionId,
    stepId = null,
    source = EvidenceSource.TOOL_RESULT,
    reference = "tool-trace-$id",
    summary = "Summary $id",
    createdAt = 2_000L,
  )

  private fun event(id: String, type: PlanEventType, createdAt: Long, planId: String = "plan-1") = PlanEvent(
    id = id,
    planId = planId,
    version = null,
    stepId = null,
    type = type,
    payload = JsonObject(emptyMap()),
    createdAt = createdAt,
  )

  // --- state aggregation -----------------------------------------------------------------------------

  @Test
  fun `select loads objective version status and step dependsOn`() = runTest(testDispatcher) {
    insertConversation()
    val steps = listOf(step(id = "s1"), step(id = "s2", dependsOn = setOf("s1")))
    repository.saveProposal(plan(), version(steps = steps))
    repository.approve("plan-1", 1)

    vm.select("plan-1")
    val state = vm.state.first { it.objective != null }

    assertEquals("Objective plan-1", state.objective)
    assertEquals(PlanStatus.ACTIVE, state.status)
    assertEquals(1, state.version)
    assertEquals(listOf("s1", "s2"), state.steps.map { it.id })
    assertEquals(setOf("s1"), state.steps[1].dependsOn)
    assertEquals(listOf("assumption-1"), state.assumptions)
    assertEquals(listOf("constraint-1"), state.constraints)
  }

  @Test
  fun `a criterion is satisfied once evidence references it, not before`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version(criteria = listOf(criterion(id = "c1"), criterion(id = "c2"))))
    repository.approve("plan-1", 1)

    vm.select("plan-1")
    val beforeEvidence = vm.state.first { it.criteria.isNotEmpty() }
    assertFalse(beforeEvidence.criteria.single { it.id == "c1" }.satisfied)

    repository.addEvidence(evidence(criterionId = "c1"))
    val afterEvidence = vm.state.first { it.criteria.any { c -> c.id == "c1" && c.satisfied } }

    assertTrue(afterEvidence.criteria.single { it.id == "c1" }.satisfied)
    assertFalse("unreferenced criterion c2 must stay unsatisfied", afterEvidence.criteria.single { it.id == "c2" }.satisfied)
  }

  @Test
  fun `events load newest first`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version())
    repository.appendEvent(event(id = "e1", type = PlanEventType.CREATED, createdAt = 100L))
    repository.appendEvent(event(id = "e2", type = PlanEventType.PROPOSED, createdAt = 200L))

    vm.select("plan-1")
    val state = vm.state.first { it.events.size == 2 }

    assertEquals(listOf("e2", "e1"), state.events.map { it.id })
  }

  // --- actions -> PlanCommands (Task 5), not a second lifecycle mechanism ----------------------------

  @Test
  fun `pause dispatches PlanCommands pause and tears down the scheduler for the selected plan`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version())
    repository.approve("plan-1", 1)
    vm.select("plan-1")
    vm.state.first { it.status == PlanStatus.ACTIVE }

    vm.pause()
    val state = vm.state.first { it.status == PlanStatus.PAUSED }

    assertEquals(PlanStatus.PAUSED, state.status)
    assertEquals(listOf("plan-1"), scheduler.cancelled)
  }

  @Test
  fun `resume dispatches PlanCommands resume for the selected plan`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version())
    repository.approve("plan-1", 1)
    repository.pause("plan-1")
    vm.select("plan-1")
    vm.state.first { it.status == PlanStatus.PAUSED }

    vm.resume()
    val state = vm.state.first { it.status == PlanStatus.ACTIVE }

    assertEquals(PlanStatus.ACTIVE, state.status)
  }

  @Test
  fun `cancel dispatches PlanCommands cancel for the selected plan`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version())
    repository.approve("plan-1", 1)
    vm.select("plan-1")
    vm.state.first { it.status == PlanStatus.ACTIVE }

    vm.cancel()
    val state = vm.state.first { it.status == PlanStatus.CANCELLED }

    assertEquals(PlanStatus.CANCELLED, state.status)
  }

  @Test
  fun `canPause canResume canCancel reflect the current status`() = runTest(testDispatcher) {
    insertConversation()
    repository.saveProposal(plan(), version())

    vm.select("plan-1")
    val awaiting = vm.state.first { it.status != null }
    assertFalse(awaiting.canPause)
    assertFalse(awaiting.canResume)
    assertTrue(awaiting.canCancel)

    repository.approve("plan-1", 1)
    val active = vm.state.first { it.status == PlanStatus.ACTIVE }
    assertTrue(active.canPause)
    assertFalse(active.canResume)
    assertTrue(active.canCancel)
  }

  @Test
  fun `an action against an unknown plan surfaces actionError instead of crashing`() = runTest(testDispatcher) {
    vm.select("missing-plan")

    vm.pause()
    val state = vm.state.first { it.actionError != null }

    assertTrue(state.actionError!!.isNotBlank())
  }

  // --- initial / not-found state ----------------------------------------------------------------------

  @Test
  fun `initial state before any select is loading and not not-found`() {
    val state = vm.state.value
    assertTrue(state.isLoading)
    assertFalse(state.notFound)
  }
}
