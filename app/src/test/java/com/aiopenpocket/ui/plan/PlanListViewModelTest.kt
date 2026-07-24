package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * [PlanListViewModel] round-tripped through a real in-memory Room DB, mirroring
 * [com.aiopenpocket.agent.core.plan.PlanRepositoryTest]/[com.aiopenpocket.ui.agents.detail.AgentDetailViewModelTest]'s
 * own setup. Proves the three-section bucketing (design spec section 15: "список active/completed/
 * paused plans") and that `stepsCompleted`/`stepsTotal` come from PERSISTED [PlanStepState] rows, not
 * just the version's own step count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanListViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var vm: PlanListViewModel
  private var now = 10_000L

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    // NOTE: Room executors deliberately NOT pinned here (unlike the sibling ViewModel tests). This
    // ViewModel's flow topology deadlocks/misorders under an eager UnconfinedTestDispatcher-backed Room
    // executor (2 tests fail), so it keeps Room's default executor. Its flake exposure is left as a
    // follow-up needing a StandardTestDispatcher-based rework, not this quick pin.
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    vm = PlanListViewModel(repository)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    db.close()
  }

  // --- fixtures (mirrors PlanRepositoryTest's own builder style) --------------------------------

  private suspend fun insertConversation(id: String) {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation $id"))
  }

  private fun plan(id: String, agentId: String = "agent-1", conversationId: String = id) = AgentPlan(
    id = id,
    agentId = agentId,
    conversationId = conversationId,
    horizon = PlanHorizon.SINGLE_RUN,
    status = PlanStatus.DRAFT,
    approvedVersion = null,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  private fun step(id: String = "step-1") = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = emptySet(),
    expectedTools = emptySet(),
    schedule = null,  )

  private fun version(planId: String, version: Int = 1, steps: List<PlanStep> = listOf(step())) = PlanVersion(
    planId = planId,
    version = version,
    objective = "Objective $planId",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = steps,
    criteria = emptyList(),
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  @Test
  fun `select buckets plans by status into active paused completed`() = runTest(testDispatcher) {
    // Each approved plan gets its OWN conversation - PlanRepository.approve refuses a second ACTIVE
    // plan in the same conversation, and that constraint is not what this test is about.
    insertConversation("plan-a")
    insertConversation("plan-b")
    insertConversation("plan-c")
    insertConversation("plan-d")

    repository.saveProposal(plan(id = "plan-a"), version(planId = "plan-a")) // AWAITING_APPROVAL -> active
    repository.saveProposal(plan(id = "plan-b"), version(planId = "plan-b"))
    repository.approve("plan-b", 1)
    repository.pause("plan-b") // PAUSED -> paused
    repository.saveProposal(plan(id = "plan-c"), version(planId = "plan-c"))
    repository.approve("plan-c", 1) // ACTIVE -> active
    repository.saveProposal(plan(id = "plan-d"), version(planId = "plan-d"))
    repository.approve("plan-d", 1)
    repository.cancel("plan-d") // CANCELLED -> completed

    vm.select("agent-1")
    val state = vm.state.first { it.active.size + it.paused.size + it.completed.size == 4 }

    assertEquals(setOf("plan-a", "plan-c"), state.active.map { it.planId }.toSet())
    assertEquals(listOf("plan-b"), state.paused.map { it.planId })
    assertEquals(listOf("plan-d"), state.completed.map { it.planId })
    assertFalse(state.isLoading)
  }

  @Test
  fun `initial state before any select is loading with empty sections`() {
    val state = vm.state.value
    assertTrue(state.isLoading)
    assertTrue(state.active.isEmpty())
    assertTrue(state.paused.isEmpty())
    assertTrue(state.completed.isEmpty())
  }

  @Test
  fun `a row's step counts reflect persisted step state, not just the version's step count`() = runTest(testDispatcher) {
    insertConversation("plan-x")
    val steps = listOf(step(id = "s1"), step(id = "s2"), step(id = "s3"))
    repository.saveProposal(plan(id = "plan-x"), version(planId = "plan-x", steps = steps))
    repository.approve("plan-x", 1)
    repository.updateStepState(
      PlanStepState(planId = "plan-x", version = 1, stepId = "s1", status = StepStatus.COMPLETED, attempts = 1, nextDueAt = null, startedAt = null, completedAt = null),
    )
    repository.updateStepState(
      PlanStepState(planId = "plan-x", version = 1, stepId = "s2", status = StepStatus.SKIPPED, attempts = 0, nextDueAt = null, startedAt = null, completedAt = null),
    )

    vm.select("agent-1")
    val state = vm.state.first { it.active.isNotEmpty() }

    val row = state.active.single { it.planId == "plan-x" }
    assertEquals(2, row.stepsCompleted)
    assertEquals(3, row.stepsTotal)
  }

  @Test
  fun `plans for a different agent never leak into this agent's sections`() = runTest(testDispatcher) {
    insertConversation("conv-mine")
    insertConversation("conv-theirs")
    repository.saveProposal(
      plan(id = "plan-mine", agentId = "agent-1", conversationId = "conv-mine"),
      version(planId = "plan-mine"),
    )
    repository.saveProposal(
      plan(id = "plan-theirs", agentId = "agent-2", conversationId = "conv-theirs"),
      version(planId = "plan-theirs"),
    )

    vm.select("agent-1")
    val state = vm.state.first { it.active.isNotEmpty() }

    assertEquals(listOf("plan-mine"), state.active.map { it.planId })
  }

  @Test
  fun `re-selecting the same agent id is a no-op`() = runTest(testDispatcher) {
    insertConversation("plan-a")
    repository.saveProposal(plan(id = "plan-a"), version(planId = "plan-a"))

    vm.select("agent-1")
    vm.state.first { it.active.isNotEmpty() }
    vm.select("agent-1") // same id again - must not reset isLoading/clear the already-loaded section

    assertFalse(vm.state.value.isLoading)
    assertEquals(listOf("plan-a"), vm.state.value.active.map { it.planId })
  }
}
