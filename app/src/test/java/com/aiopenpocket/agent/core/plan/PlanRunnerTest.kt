package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.plan.execution.MemoryFactLookup
import com.aiopenpocket.agent.core.plan.execution.PlanEvidenceSink
import com.aiopenpocket.agent.core.plan.execution.PlanExecutor
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.plan.execution.PlanRunRequest
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunner
import com.aiopenpocket.agent.core.plan.execution.PlanStepRunner
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * [PlanRunner] (Task 10) round-tripped through a REAL (in-memory) Room [PlanRepository]/
 * [AgentHookRepository] and a REAL [PlanExecutor]/[PlanVerifier] — mirrors [PlanExecutorTest]'s own
 * setup — with a [ScriptedStepRunner] standing in for [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * (via the same [PlanStepRunner] seam [PlanExecutor] itself uses) so no LLM/tool-executor fake graph
 * is needed. Most fixtures configure ZERO plan-level criteria — with none to prove, every clean step
 * finish trivially becomes [StepStatus.COMPLETED] (`PlanVerifier.decideOutcome`'s own
 * `unsatisfiedRequired.isEmpty() -> Proven` short-circuit) — which keeps the SELECTION-focused tests
 * (topological order, due dates, budget, pinning) from also having to fuss with tool-trace evidence;
 * a few tests add a real [VerifierType.TOOL_RESULT]/[VerifierType.USER_CONFIRMATION] criterion where
 * the point IS to exercise evidence-gated completion/blocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRunnerTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var toolRegistry: ToolRegistry
  private lateinit var stepRunner: ScriptedStepRunner
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    hookRepository = AgentHookRepository(db.agentHookDao())
    toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool")))
    stepRunner = ScriptedStepRunner()
  }

  @After
  fun tearDown() = db.close()

  // --- fakes ------------------------------------------------------------------------------------

  private class FakeTool(override val name: String) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  private object NoAssets : AssetSource {
    override fun load(path: String): String? = null
  }

  private object NoMemoryFacts : MemoryFactLookup {
    override suspend fun getFactsByKeyPrefix(prefix: String): List<Fact> = emptyList()
  }

  private object UnusedLlmClient : LlmClient {
    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> =
      throw IllegalStateException("LlmClient must not be called: no MODEL_ASSESSMENT criterion in this file.")
  }

  private class FakeAgentResolver(private val agent: AgentDefinition?) : AgentResolver {
    override suspend fun resolve(id: String?): AgentDefinition? = agent.takeIf { id != null }
    override fun observeRoster() = throw NotImplementedError("Not needed by PlanRunner.")
  }

  /**
   * Stands in for [com.aiopenpocket.agent.loop.ReactLoopRunner.run] via the [PlanStepRunner] seam —
   * mirrors [PlanExecutorTest]'s own `FakeStepRunner`, extended two ways for [PlanRunner]'s own
   * tests: [script] is keyed by STEP ID (extracted from the exact `"Выполни шаг [id] ..."` user
   * message [PlanExecutor.buildStepMessages] always writes — see that method's own last line) so one
   * runner instance can behave differently per step across a multi-step plan; [suspendGate], when
   * set, makes every call await it before returning (the "one active execution per conversation"
   * concurrency test's only way to park a run mid-step under `kotlinx-coroutines-test`).
   */
  private class ScriptedStepRunner : PlanStepRunner {
    var script: (String) -> AgentResult = { finishedResult() }
    var suspendGate: CompletableDeferred<Unit>? = null
    val calledStepIds = mutableListOf<String>()

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
      val stepId = Regex("""\[(.*?)]""").find(messages.last { it.role == "user" }.content.orEmpty())
        ?.groupValues?.get(1).orEmpty()
      calledStepIds += stepId
      suspendGate?.await()
      return script(stepId)
    }
  }

  // --- fixtures (mirrors PlanExecutorTest/PlanRepositoryTest's own builders) ---------------------

  private suspend fun insertConversation(id: String = "conv-1") {
    db.conversationDao().insert(ConversationEntity(id = id, title = "Test conversation"))
  }

  private fun plan(
    id: String = "plan-1",
    conversationId: String = "conv-1",
    agentId: String? = "agent-1",
    horizon: PlanHorizon = PlanHorizon.SINGLE_RUN,
  ) = AgentPlan(
    id = id, agentId = agentId, conversationId = conversationId, horizon = horizon,
    status = PlanStatus.DRAFT, approvedVersion = null, activeStepId = null,
    createdAt = 1_000L, updatedAt = 1_000L,
  )

  private fun step(
    id: String = "step-1",
    dependsOn: Set<String> = emptySet(),
    schedule: PlanSchedule? = null,
  ) = PlanStep(
    id = id, title = "Title $id", instruction = "Instruction $id",
    dependsOn = dependsOn, expectedTools = setOf("memory_tool"), schedule = schedule,
    completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun toolResultCriterion(id: String = "c1", toolName: String = "memory_tool", required: Boolean = true) = PlanCriterion(
    id = id, description = "Критерий $id: вызван $toolName",
    verifier = CriterionVerifier(type = VerifierType.TOOL_RESULT, config = buildJsonObject { put("toolName", toolName) }),
    required = required,
  )

  private fun userConfirmationCriterion(id: String = "c1") = PlanCriterion(
    id = id, description = "Критерий $id: подтверждение пользователя",
    verifier = CriterionVerifier(type = VerifierType.USER_CONFIRMATION, config = buildJsonObject { put("note", "confirm") }),
    required = true,
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = emptyList(),
  ) = PlanVersion(
    planId = planId, version = version, objective = "Objective", assumptions = emptyList(),
    constraints = emptyList(), steps = steps, criteria = criteria, createdBy = PlanAuthor.AGENT, approvedAt = null,
  )

  private fun stepState(stepId: String, status: StepStatus, nextDueAt: Long? = null, planId: String = "plan-1", version: Int = 1) =
    PlanStepState(planId = planId, version = version, stepId = stepId, status = status, attempts = 0, nextDueAt = nextDueAt, startedAt = null, completedAt = null)

  private fun agentDefinition() = AgentDefinition(
    id = "agent-1", source = AgentSource.USER, name = "Тестовый агент",
    description = "Тестовый агент для PlanRunnerTest.", iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.", toolNames = setOf("memory_tool"), skillIds = emptySet(),
    defaultMode = AgentMode.GOAL, allowedModes = setOf(AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:agent-1:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(), hooks = emptyList(), configVersion = 1L, status = AgentStatus.PUBLISHED,
  )

  private fun successToolTrace(toolName: String = "memory_tool") = listOf(
    ToolTrace(iteration = 1, toolName = toolName, arguments = emptyMap(), result = ToolResult.Success("ok")),
  )

  /** Real repository round-trip: DRAFT -> AWAITING_APPROVAL -> ACTIVE, approvedVersion=1. */
  private suspend fun approvedSnapshot(
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = emptyList(),
    planId: String = "plan-1",
    horizon: PlanHorizon = PlanHorizon.SINGLE_RUN,
  ): PlanSnapshot {
    insertConversation()
    repository.saveProposal(plan(id = planId, horizon = horizon), version(planId = planId, steps = steps, criteria = criteria))
    repository.approve(planId, 1)
    return requireNotNull(repository.snapshot(planId))
  }

  private fun executor() = PlanExecutor(repository, toolRegistry, stepRunner, NoAssets, verifier(), hookRepository) { now }

  private fun verifier() = PlanVerifier(PlanEvidenceSink(repository::addEvidence), NoMemoryFacts, UnusedLlmClient, NoAssets) { now }

  private fun planRunner(agent: AgentDefinition? = agentDefinition()) =
    PlanRunner(repository, executor(), FakeAgentResolver(agent)) { now }

  private fun request(planId: String, requestedStepId: String? = null, budget: PlanRunBudget = PlanRunBudget(), conversationId: String = "conv-1") =
    PlanRunRequest(conversationId = conversationId, planId = planId, requestedStepId = requestedStepId, budget = budget)

  private companion object {
    fun finishedResult(text: String = "ok", trace: List<ToolTrace> = emptyList()) =
      AgentResult(text = text, toolTrace = trace, state = AgentState.FINISHED, iterations = 1)
  }

  // --- single-step happy path: COMPLETED whole plan ----------------------------------------------

  @Test
  fun `run executes the only step, proves its criterion and completes the whole plan`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()), criteria = listOf(toolResultCriterion()))
    stepRunner.script = { finishedResult(trace = successToolTrace()) }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.COMPLETED, result.outcome)
    assertEquals(listOf("step-1"), result.executedStepIds)
    assertNull(result.blockedReason)
    assertEquals(PlanStatus.COMPLETED, repository.snapshot(snapshot.plan.id)!!.plan.status)
  }

  @Test
  fun `run does not complete the whole plan while a required criterion still lacks evidence`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()), criteria = listOf(toolResultCriterion(toolName = "other_tool_never_called")))
    stepRunner.script = { finishedResult(trace = successToolTrace("memory_tool")) } // calls the WRONG tool
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertEquals(PlanStatus.ACTIVE, repository.snapshot(snapshot.plan.id)!!.plan.status)
  }

  // --- topological selection ----------------------------------------------------------------------

  @Test
  fun `run does not select a step whose dependsOn is unsatisfied`() = runTest {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snapshot = approvedSnapshot(steps = listOf(step(id = "step-1"), dependent))
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id, budget = PlanRunBudget(maxStepsPerRun = 5)))

    // Both steps are criteria-less (trivially COMPLETED on a clean finish) so a 5-step budget is
    // enough to run step-1 then step-2 in the SAME call, in dependency order — never step-2 first.
    assertEquals(listOf("step-1", "step-2"), result.executedStepIds)
    assertEquals(listOf("step-1", "step-2"), stepRunner.calledStepIds)
  }

  @Test
  fun `a plan whose only runnable step is blocked by an unmet dependency stays PARTIAL forever without throwing`() = runTest {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snapshot = approvedSnapshot(steps = listOf(dependent))
    // step-1 does not exist as a real step in this version at all — step-2 can never become runnable.
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertTrue(result.executedStepIds.isEmpty())
    assertEquals(0, stepRunner.calledStepIds.size)
  }

  // --- budget -------------------------------------------------------------------------------------

  @Test
  fun `run stops with PARTIAL once maxStepsPerRun is used up, leaving further steps for a later run`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step(id = "step-1"), step(id = "step-2")))
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    val first = runner.run(request(snapshot.plan.id, budget = PlanRunBudget(maxStepsPerRun = 1)))
    assertEquals(PlanRunOutcome.PARTIAL, first.outcome)
    assertEquals(listOf("step-1"), first.executedStepIds)

    val second = runner.run(request(snapshot.plan.id, budget = PlanRunBudget(maxStepsPerRun = 1)))
    assertEquals(listOf("step-2"), second.executedStepIds)
    // Both criteria-less steps are now COMPLETED -> the whole (criteria-less) plan completes too.
    assertEquals(PlanRunOutcome.COMPLETED, second.outcome)
  }

  // --- due dates (SINGLE_RUN vs PROGRAM) -----------------------------------------------------------

  @Test
  fun `a SINGLE_RUN step with no schedule is always due`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step(id = "step-1", schedule = null)), horizon = PlanHorizon.SINGLE_RUN)
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(listOf("step-1"), result.executedStepIds)
  }

  @Test
  fun `a PROGRAM step not yet due is not selected`() = runTest {
    val scheduled = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))
    val snapshot = approvedSnapshot(steps = listOf(scheduled), horizon = PlanHorizon.PROGRAM)
    // Seed progress with a nextDueAt in the FUTURE relative to `now`.
    repository.updateStepState(stepState("step-1", StepStatus.READY, nextDueAt = now + 60_000))
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertTrue(result.executedStepIds.isEmpty())
    assertEquals(0, stepRunner.calledStepIds.size)
  }

  @Test
  fun `a PROGRAM step becomes selectable once its nextDueAt has passed`() = runTest {
    val scheduled = step(id = "step-1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))
    val snapshot = approvedSnapshot(steps = listOf(scheduled), horizon = PlanHorizon.PROGRAM)
    repository.updateStepState(stepState("step-1", StepStatus.READY, nextDueAt = now + 60_000))
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    now += 61_000 // advance the injected clock past nextDueAt
    val result = runner.run(request(snapshot.plan.id))

    assertEquals(listOf("step-1"), result.executedStepIds)
  }

  // --- pre-flight plan-status gate ------------------------------------------------------------------

  @Test
  fun `run refuses a PAUSED plan without touching PlanExecutor`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()))
    repository.pause(snapshot.plan.id)
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertTrue(result.blockedReason!!.contains("paused", ignoreCase = true))
    assertEquals(0, stepRunner.calledStepIds.size)
  }

  @Test
  fun `run refuses a plan that has never been approved`() = runTest {
    insertConversation()
    repository.saveProposal(plan(), version())
    val runner = planRunner()

    val result = runner.run(request("plan-1"))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertEquals(0, stepRunner.calledStepIds.size)
  }

  // --- step outcomes that stop the run ---------------------------------------------------------------

  @Test
  fun `a FAILED step (stream error) stops the run with outcome FAILED`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()))
    stepRunner.script = { AgentResult(text = "", toolTrace = emptyList(), state = AgentState.ERROR, iterations = 1, errorMessage = "boom") }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.FAILED, result.outcome)
    assertEquals(listOf("step-1"), result.executedStepIds)
    assertEquals(StepStatus.FAILED, repository.snapshot(snapshot.plan.id)!!.stepStates.single().status)
  }

  @Test
  fun `a step blocked on a required USER_CONFIRMATION criterion stops the run with PARTIAL`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()), criteria = listOf(userConfirmationCriterion()))
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertEquals(listOf("step-1"), result.executedStepIds)
    assertEquals(StepStatus.BLOCKED, repository.snapshot(snapshot.plan.id)!!.stepStates.single().status)
  }

  // --- requestedStepId pinning (Task 11's typed heartbeat target) ------------------------------------

  @Test
  fun `requestedStepId pins selection to that one step even when an earlier step is also runnable`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step(id = "step-1"), step(id = "step-2")))
    stepRunner.script = { finishedResult() }
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id, requestedStepId = "step-2"))

    assertEquals(listOf("step-2"), result.executedStepIds)
    assertEquals(listOf("step-2"), stepRunner.calledStepIds)
  }

  @Test
  fun `requestedStepId that is not runnable yet reports a specific blocked reason instead of falling back`() = runTest {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snapshot = approvedSnapshot(steps = listOf(step(id = "step-1"), dependent))
    val runner = planRunner()

    val result = runner.run(request(snapshot.plan.id, requestedStepId = "step-2"))

    assertEquals(PlanRunOutcome.PARTIAL, result.outcome)
    assertTrue(result.blockedReason!!.contains("step-2"))
    assertEquals(0, stepRunner.calledStepIds.size)
  }

  // --- concurrency: one active execution per conversation ----------------------------------------

  @Test
  fun `a second concurrent run for the same plan is refused while the first is still in flight`() = runTest {
    val snapshot = approvedSnapshot(steps = listOf(step()))
    val gate = CompletableDeferred<Unit>()
    stepRunner.suspendGate = gate
    stepRunner.script = { finishedResult() }
    val runner = planRunner()
    var firstResult: PlanRunResult? = null

    val firstJob = launch { firstResult = runner.run(request(snapshot.plan.id)) }
    advanceUntilIdle() // first call reaches stepRunner.run() and parks on `gate`

    val second = runner.run(request(snapshot.plan.id))
    assertEquals(PlanRunOutcome.PARTIAL, second.outcome)
    assertTrue(second.blockedReason!!.contains("already executing"))
    assertTrue(second.executedStepIds.isEmpty())

    gate.complete(Unit)
    firstJob.join()
    assertEquals(PlanRunOutcome.COMPLETED, firstResult?.outcome)
  }
}
