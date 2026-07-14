package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookContext
import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.plan.execution.MemoryFactLookup
import com.aiopenpocket.agent.core.plan.execution.PlanCommands
import com.aiopenpocket.agent.core.plan.execution.PlanEvidenceSink
import com.aiopenpocket.agent.core.plan.execution.PlanExecutor
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.execution.PlanStepRunner
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 9 (Structured Plan Engine): the six plan-lifecycle [HookEvent]s actually reach the SAME
 * declarative [HookEngine] turn-level hooks use (see [PlanCommands]/[PlanExecutor]'s own kdoc
 * "Plan-lifecycle hooks"), fire at the documented points, apply only the declarative effects the
 * design allows, and can never mutate an approved [PlanVersion]. Mirrors
 * [com.aiopenpocket.agent.loop.ReactLoopHooksTest]'s own "wire to a REAL HookEngine + real (in-memory
 * Room) AgentHookRepository, not a fake" approach, plus [PlanExecutorTest]/[PlanApprovalTest]'s Room
 * fixtures for [PlanRepository]/[PlanExecutor]/[PlanCommands].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanHookIntegrationTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var toolRegistry: ToolRegistry
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    hookRepository = AgentHookRepository(db.agentHookDao())
    toolRegistry = ToolRegistry(emptySet())
  }

  @After
  fun tearDown() = db.close()

  // --- fakes (mirrors PlanExecutorTest's own) ----------------------------------------------------

  private class NoopPlanScheduleSync : PlanScheduleSync {
    override suspend fun sync(planId: String) = Unit
    override suspend fun cancel(planId: String) = Unit
  }

  private object NoAssets : AssetSource {
    override fun load(path: String): String? = null
  }

  /** No plan in this file configures a MEMORY_FACT criterion — [PlanVerifier] must never call this. */
  private object NoMemoryFacts : MemoryFactLookup {
    override suspend fun getFactsByKeyPrefix(prefix: String): List<Fact> = emptyList()
  }

  /** No plan in this file configures a MODEL_ASSESSMENT criterion — [PlanVerifier] must never call this. */
  private object UnusedLlmClient : LlmClient {
    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> =
      throw IllegalStateException("LlmClient must not be called: this test has no MODEL_ASSESSMENT criteria.")
  }

  private fun verifier() = PlanVerifier(PlanEvidenceSink(repository::addEvidence), NoMemoryFacts, UnusedLlmClient, NoAssets) { now }

  private fun commands(scheduler: PlanScheduleSync = NoopPlanScheduleSync()) = PlanCommands(repository, scheduler, hookRepository)

  private fun executor(runner: PlanStepRunner) = PlanExecutor(repository, toolRegistry, runner, NoAssets, verifier(), hookRepository) { now }

  /** Records every argument [PlanExecutor] called it with — same seam [PlanExecutorTest] uses instead of a real [com.aiopenpocket.agent.loop.ReactLoopRunner]. */
  private class FakeStepRunner(private val result: AgentResult) : PlanStepRunner {
    var callCount = 0
      private set
    var lastMessages: List<ChatMessage>? = null
      private set

    override suspend fun run(
      conversationId: String,
      messages: MutableList<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
      maxIterations: Int,
      agent: AgentDefinition?,
      executionMode: ExecutionMode,
      interactiveApproval: Boolean,
      maxToolCalls: Int,
      emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
      callCount++
      lastMessages = messages.toList()
      return result
    }
  }

  private fun finishedResult(text: String = "ok") = AgentResult(text = text, toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 1)

  // --- fixtures (mirrors PlanExecutorTest/PlanApprovalTest's own) ------------------------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(
    id: String = "plan-1",
    agentId: String? = "agent-1",
    status: PlanStatus = PlanStatus.DRAFT,
    approvedVersion: Int? = null,
  ) = AgentPlan(
    id = id, agentId = agentId, conversationId = "conv-1", horizon = PlanHorizon.SINGLE_RUN,
    status = status, approvedVersion = approvedVersion, activeStepId = null, createdAt = 1_000L, updatedAt = 1_000L,
  )

  private fun step(id: String = "step-1") = PlanStep(
    id = id, title = "Title $id", instruction = "Instruction $id", dependsOn = emptySet(),
    expectedTools = emptySet(), schedule = null, completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = emptyList(),
  ) = PlanVersion(
    planId = planId, version = version, objective = "Objective v$version", assumptions = emptyList(), constraints = emptyList(),
    steps = steps, criteria = criteria, createdBy = PlanAuthor.AGENT, approvedAt = null,
  )

  /** Real repository round-trip: DRAFT -> AWAITING_APPROVAL -> ACTIVE, approvedVersion=1 — mirrors [PlanExecutorTest]'s own helper. */
  private suspend fun approvedSnapshot(steps: List<PlanStep> = listOf(step()), planId: String = "plan-1"): PlanSnapshot {
    insertConversation()
    repository.saveProposal(plan(id = planId), version(planId = planId, steps = steps))
    repository.approve(planId, 1)
    return requireNotNull(repository.snapshot(planId))
  }

  private fun agent(hooks: List<AgentHook> = emptyList(), id: String = "agent-1") = AgentDefinition(
    id = id, source = AgentSource.USER, name = "Тестовый агент", description = "Тестовый агент для PlanHookIntegrationTest.",
    iconKey = "sparkles", systemPrompt = "Ты — тестовый агент.", toolNames = emptySet(), skillIds = emptySet(),
    defaultMode = AgentMode.GOAL, allowedModes = setOf(AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(), hooks = hooks, configVersion = 1L, status = AgentStatus.PUBLISHED,
  )

  private fun hook(
    id: String,
    event: HookEvent,
    actionType: HookActionType,
    actionJson: String = "{}",
    agentId: String = "agent-1",
    priority: Int = 0,
  ) = AgentHook(
    id = id, agentId = agentId, event = event, conditionType = "always", conditionJson = "{}",
    actionType = actionType, actionJson = actionJson, priority = priority, enabled = true,
  )

  // --- ON_PLAN_APPROVED fires and journals (PlanCommands) --------------------------------------------

  @Test
  fun `ON_PLAN_APPROVED hook fires and is journaled when a plan is approved`() = runTest {
    hookRepository.replaceForAgent("agent-1", listOf(hook("h-approved", HookEvent.ON_PLAN_APPROVED, HookActionType.LOG, """{"text":"approved"}""")))
    insertConversation()
    repository.saveProposal(plan(), version())

    commands().approve("plan-1", 1)

    val runs = hookRepository.getRunsForHook("h-approved")
    assertTrue("ON_PLAN_APPROVED hook must have fired and been journaled", runs.isNotEmpty())
    assertEquals(HookActionType.LOG.name, runs.single().status)
  }

  @Test
  fun `approve does not fire ON_PLAN_APPROVED for a plan with no bound agent`() = runTest {
    hookRepository.replaceForAgent("agent-1", listOf(hook("h-approved", HookEvent.ON_PLAN_APPROVED, HookActionType.LOG)))
    insertConversation()
    repository.saveProposal(plan(agentId = null), version())

    commands().approve("plan-1", 1)

    assertTrue("no agentId on the plan means no hook lookup at all", hookRepository.getRunsForHook("h-approved").isEmpty())
  }

  // --- BEFORE_PLAN_STEP ADD_INSTRUCTION: injects into the step turn, never mutates the approved version --

  @Test
  fun `BEFORE_PLAN_STEP ADD_INSTRUCTION reaches the step turn's prompt but leaves the approved PlanVersion untouched`() = runTest {
    val instructionHook = hook("h-instr", HookEvent.BEFORE_PLAN_STEP, HookActionType.ADD_INSTRUCTION, """{"text":"EPHEMERAL_STEP_HOOK_TEXT"}""")
    val snapshot = approvedSnapshot()
    val versionBefore = requireNotNull(repository.snapshot("plan-1")).version
    val runner = FakeStepRunner(finishedResult())

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(hooks = listOf(instructionHook)))

    val sentSystemMessage = runner.lastMessages.orEmpty().first { it.role == "system" }
    assertTrue("the hook's instruction must reach the step turn's prompt", sentSystemMessage.content.orEmpty().contains("EPHEMERAL_STEP_HOOK_TEXT"))
    assertTrue("the hook firing must be journaled", hookRepository.getRunsForHook("h-instr").isNotEmpty())

    val versionAfter = requireNotNull(repository.snapshot("plan-1")).version
    assertEquals("a plan hook must never change the approved PlanVersion's content", versionBefore, versionAfter)
  }

  // --- BEFORE_PLAN_STEP DENY: blocks the step via the state machine, never runs it -------------------

  @Test
  fun `BEFORE_PLAN_STEP DENY blocks the step through PlanStateMachine instead of running it`() = runTest {
    val denyHook = hook("h-deny", HookEvent.BEFORE_PLAN_STEP, HookActionType.DENY)
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult())

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(hooks = listOf(denyHook)))

    assertEquals("the step turn must never actually run once denied", 0, runner.callCount)
    assertTrue(result.error.orEmpty().contains("DENY"))
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
    assertTrue("the DENY firing must be journaled", hookRepository.getRunsForHook("h-deny").isNotEmpty())
  }

  // --- AFTER_PLAN_STEP / ON_PLAN_COMPLETED / ON_PLAN_BLOCKED: fire at the right point, mutually exclusive --

  @Test
  fun `a clean single-step finish fires AFTER_PLAN_STEP then ON_PLAN_COMPLETED, never ON_PLAN_BLOCKED, sharing one runId`() = runTest {
    val afterHook = hook("h-after", HookEvent.AFTER_PLAN_STEP, HookActionType.LOG)
    val completedHook = hook("h-completed", HookEvent.ON_PLAN_COMPLETED, HookActionType.LOG)
    val blockedHook = hook("h-blocked", HookEvent.ON_PLAN_BLOCKED, HookActionType.LOG)
    val snapshot = approvedSnapshot() // default: exactly one step, "step-1"
    val runner = FakeStepRunner(finishedResult())

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(hooks = listOf(afterHook, completedHook, blockedHook)))

    assertNull(result.error)
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.COMPLETED, state.status)

    val afterRuns = hookRepository.getRunsForHook("h-after")
    val completedRuns = hookRepository.getRunsForHook("h-completed")
    assertTrue("AFTER_PLAN_STEP must fire once the outcome is persisted", afterRuns.isNotEmpty())
    assertTrue("the only step just completed, so ON_PLAN_COMPLETED must fire too", completedRuns.isNotEmpty())
    assertTrue("the step was not blocked, so ON_PLAN_BLOCKED must never fire", hookRepository.getRunsForHook("h-blocked").isEmpty())
    assertEquals("BEFORE/AFTER_PLAN_STEP firings for one step attempt share one runId", afterRuns.first().runId, completedRuns.first().runId)
  }

  @Test
  fun `a budget-stopped run fires AFTER_PLAN_STEP then ON_PLAN_BLOCKED, never ON_PLAN_COMPLETED`() = runTest {
    val afterHook = hook("h-after2", HookEvent.AFTER_PLAN_STEP, HookActionType.LOG)
    val completedHook = hook("h-completed2", HookEvent.ON_PLAN_COMPLETED, HookActionType.LOG)
    val blockedHook = hook("h-blocked2", HookEvent.ON_PLAN_BLOCKED, HookActionType.LOG)
    val snapshot = approvedSnapshot()
    val budgetStopped = AgentResult(text = "partial", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 3, hitIterationLimit = true)
    val runner = FakeStepRunner(budgetStopped)

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(hooks = listOf(afterHook, completedHook, blockedHook)))

    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
    assertTrue(hookRepository.getRunsForHook("h-after2").isNotEmpty())
    assertTrue("blocked (not completed) outcome must fire ON_PLAN_BLOCKED", hookRepository.getRunsForHook("h-blocked2").isNotEmpty())
    assertTrue("blocked (not completed) outcome must never fire ON_PLAN_COMPLETED", hookRepository.getRunsForHook("h-completed2").isEmpty())
  }

  // --- AgentValidator: illegal action for a plan event is rejected ----------------------------------

  @Test
  fun `AgentValidator rejects an illegal action for a plan-lifecycle event`() {
    val illegal = hook("h-illegal", HookEvent.ON_PLAN_COMPLETED, HookActionType.DENY)

    val issues = AgentValidator.validate(agent(hooks = listOf(illegal)))

    assertTrue(
      "DENY is not in ON_PLAN_COMPLETED's LOG-only matrix",
      issues.any { it.code == "invalid_hook_action_for_event" },
    )
  }

  @Test
  fun `AgentValidator accepts a legal action for BEFORE_PLAN_STEP`() {
    val legal = hook("h-legal", HookEvent.BEFORE_PLAN_STEP, HookActionType.ADD_INSTRUCTION, """{"text":"hi"}""")

    val issues = AgentValidator.validate(agent(hooks = listOf(legal)))

    assertFalse(issues.any { it.code == "invalid_hook_action_for_event" })
  }

  // --- regression: pre-Task-9 turn-level matrix and HookContext defaults are unchanged --------------

  @Test
  fun `the six original turn-level events keep their exact pre-Task-9 action matrix`() {
    assertEquals(setOf(HookActionType.ADD_INSTRUCTION, HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.BEFORE_TURN))
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.AFTER_TURN))
    assertEquals(
      setOf(HookActionType.REQUIRE_APPROVAL, HookActionType.ALLOW, HookActionType.DENY, HookActionType.LOG, HookActionType.STOP),
      HookEngine.actionsAllowedFor(HookEvent.BEFORE_TOOL),
    )
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.AFTER_TOOL))
    assertEquals(setOf(HookActionType.LOG, HookActionType.STOP), HookEngine.actionsAllowedFor(HookEvent.ON_ERROR))
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_COMPLETE))
  }

  @Test
  fun `the six new plan-lifecycle events' action matrix matches the design spec exactly`() {
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_PLAN_CREATED))
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_PLAN_APPROVED))
    assertEquals(
      setOf(HookActionType.ADD_INSTRUCTION, HookActionType.REQUIRE_APPROVAL, HookActionType.DENY, HookActionType.LOG, HookActionType.STOP),
      HookEngine.actionsAllowedFor(HookEvent.BEFORE_PLAN_STEP),
    )
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.AFTER_PLAN_STEP))
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_PLAN_BLOCKED))
    assertEquals(setOf(HookActionType.LOG), HookEngine.actionsAllowedFor(HookEvent.ON_PLAN_COMPLETED))
  }

  @Test
  fun `HookContext keeps turn-level fields working with the new plan fields defaulted away`() {
    val ctx = HookContext(iteration = 3, toolName = "sms")

    assertEquals(3, ctx.iteration)
    assertEquals("sms", ctx.toolName)
    assertNull(ctx.planId)
    assertNull(ctx.planStatus)
    assertNull(ctx.stepStatus)
    assertEquals(emptyList<String>(), ctx.evidenceIds)
    assertFalse(ctx.background)
  }
}
