package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
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
import com.aiopenpocket.agent.core.plan.execution.PlanStepRunner
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
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
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * [PlanExecutor] Task 7: [PlanExecutor.execute] round-tripped through a real (in-memory) Room
 * [PlanRepository] (mirrors [PlanRepositoryTest]/[PlanContextProviderTest]'s own setup) for the
 * precondition/persistence half, a [FakeStepRunner] standing in for [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * (so no [com.aiopenpocket.agent.core.llm.LlmClient]/[com.aiopenpocket.agent.core.mcp.ToolExecutor]
 * fake graph is needed at all), plus direct, no-IO unit tests for the pure halves —
 * [PlanExecutor.Companion.isRunnable], [PlanExecutor.Companion.resolveToolScope],
 * [PlanExecutor.Companion.buildStepMessages] — that build their inputs by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanExecutorTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var toolRegistry: ToolRegistry
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var turnContextHolder: TurnContextHolder
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool"), FakeTool("other_tool")))
    hookRepository = AgentHookRepository(db.agentHookDao())
    turnContextHolder = TurnContextHolder()
  }

  @After
  fun tearDown() = db.close()

  // --- fakes ----------------------------------------------------------------------------------------

  private class FakeTool(override val name: String) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  /** Records every argument [PlanExecutor] called it with and returns a pre-scripted [AgentResult] — the "fake ReactLoopRunner" for this task, via the [PlanStepRunner] seam. When given a [holder], it also records what [TurnContextHolder] a tool WOULD read at call time — proving the step runs inside the plan agent's context, not a global/stale one. */
  private class FakeStepRunner(
    private val result: AgentResult,
    private val holder: TurnContextHolder? = null,
  ) : PlanStepRunner {
    var callCount = 0
      private set
    var lastMessages: List<ChatMessage>? = null
      private set
    var lastTools: List<FunctionSchema>? = null
      private set
    var lastMaxIterations: Int? = null
      private set
    var lastMaxToolCalls: Int? = null
      private set
    var lastInteractiveApproval: Boolean? = null
      private set
    var lastExecutionMode: ExecutionMode? = null
      private set
    var observedAgent: AgentDefinition? = null
      private set
    var observedConversationId: String? = null
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
      lastTools = tools
      lastMaxIterations = maxIterations
      lastMaxToolCalls = maxToolCalls
      lastInteractiveApproval = interactiveApproval
      lastExecutionMode = executionMode
      // What a memory/file/storage tool inside this step would resolve its namespace from.
      observedAgent = holder?.agent
      observedConversationId = holder?.conversationId
      return result
    }
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

  /** Real [PlanEvidenceSink] backed by the same in-memory [repository] every other assertion reads back from. */
  private fun verifier() = PlanVerifier(PlanEvidenceSink(repository::addEvidence), NoMemoryFacts, UnusedLlmClient, NoAssets) { now }

  private fun executor(runner: PlanStepRunner, registry: ToolRegistry = toolRegistry) =
    PlanExecutor(repository, registry, runner, NoAssets, verifier(), hookRepository, turnContextHolder) { now }

  // --- fixtures (mirrors PlanRepositoryTest/PlanContextProviderTest's own builders) ------------------

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

  private fun step(
    id: String = "step-1",
    dependsOn: Set<String> = emptySet(),
    expectedTools: Set<String> = setOf("memory_tool"),
  ) = PlanStep(
    id = id,
    title = "Title $id",
    instruction = "Instruction $id",
    dependsOn = dependsOn,
    expectedTools = expectedTools,
    schedule = null,
    completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = emptyList(),
  ) = PlanVersion(
    planId = planId,
    version = version,
    objective = "Objective",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  /** Task 8: a [PlanCriterion] whose [VerifierType.TOOL_RESULT] verifier config names [toolName] — mirrors the "toolName" config key [com.aiopenpocket.agent.core.plan.execution.DeterministicVerifierRegistry] reads. */
  private fun toolResultCriterion(id: String = "c1", toolName: String = "memory_tool", required: Boolean = true) = PlanCriterion(
    id = id,
    description = "Критерий $id: вызван $toolName",
    verifier = CriterionVerifier(type = VerifierType.TOOL_RESULT, config = buildJsonObject { put("toolName", toolName) }),
    required = required,
  )

  private fun stepState(
    stepId: String,
    status: StepStatus,
    attempts: Int = 0,
    planId: String = "plan-1",
    version: Int = 1,
    nextDueAt: Long? = null,
  ) = PlanStepState(
    planId = planId, version = version, stepId = stepId, status = status,
    attempts = attempts, nextDueAt = nextDueAt, startedAt = null, completedAt = null,
  )

  private fun agent(toolNames: Set<String> = setOf("memory_tool")) = AgentDefinition(
    id = "agent-1",
    source = AgentSource.USER,
    name = "Тестовый агент",
    description = "Тестовый агент для PlanExecutorTest.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.GOAL,
    allowedModes = setOf(AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:agent-1:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun finishedResult(text: String = "ok", toolTrace: List<ToolTrace> = emptyList()) =
    AgentResult(text = text, toolTrace = toolTrace, state = AgentState.FINISHED, iterations = 1)

  private fun errorResult(message: String) =
    AgentResult(text = "", toolTrace = emptyList(), state = AgentState.ERROR, iterations = 1, errorMessage = message)

  private fun budgetStoppedResult(hitIterationLimit: Boolean = false, stoppedByToolBudget: Boolean = false) = AgentResult(
    text = "partial", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 3,
    hitIterationLimit = hitIterationLimit, stoppedByToolBudget = stoppedByToolBudget,
  )

  /** Real repository round-trip: DRAFT -> AWAITING_APPROVAL -> ACTIVE, approvedVersion=1 — mirrors [PlanContextProviderTest]'s own setup flow. */
  private suspend fun approvedSnapshot(
    steps: List<PlanStep> = listOf(step()),
    planId: String = "plan-1",
    criteria: List<PlanCriterion> = emptyList(),
  ): PlanSnapshot {
    insertConversation()
    repository.saveProposal(plan(id = planId), version(planId = planId, steps = steps, criteria = criteria))
    repository.approve(planId, 1)
    return requireNotNull(repository.snapshot(planId))
  }

  // --- isRunnable: pure, no I/O -----------------------------------------------------------------------

  @Test
  fun `isRunnable is true when a step has no dependsOn`() {
    assertTrue(PlanExecutor.isRunnable(step(dependsOn = emptySet()), emptyList()))
  }

  @Test
  fun `isRunnable is false when a dependsOn step has no recorded state at all`() {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    assertFalse(PlanExecutor.isRunnable(dependent, emptyList()))
  }

  @Test
  fun `isRunnable is false when a dependsOn step is not COMPLETED or SKIPPED`() {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val states = listOf(stepState(stepId = "step-1", status = StepStatus.EXECUTING))
    assertFalse(PlanExecutor.isRunnable(dependent, states))
  }

  @Test
  fun `isRunnable is true when all dependsOn steps are COMPLETED or SKIPPED`() {
    val dependent = step(id = "step-3", dependsOn = setOf("step-1", "step-2"))
    val states = listOf(
      stepState(stepId = "step-1", status = StepStatus.COMPLETED),
      stepState(stepId = "step-2", status = StepStatus.SKIPPED),
    )
    assertTrue(PlanExecutor.isRunnable(dependent, states))
  }

  // --- resolveToolScope: pure, no I/O ------------------------------------------------------------------

  @Test
  fun `resolveToolScope is the intersection of agent toolNames and step expectedTools`() {
    assertEquals(setOf("b", "c"), PlanExecutor.resolveToolScope(setOf("a", "b", "c"), setOf("b", "c", "d")))
  }

  @Test
  fun `resolveToolScope excludes a tool in expectedTools but not agent toolNames (hallucinated-tool guard)`() {
    val scope = PlanExecutor.resolveToolScope(setOf("memory_tool"), setOf("memory_tool", "tool_agent_does_not_have"))
    assertEquals(setOf("memory_tool"), scope)
  }

  @Test
  fun `resolveToolScope excludes a tool in agent toolNames but not step expectedTools`() {
    val scope = PlanExecutor.resolveToolScope(setOf("memory_tool", "unrelated_agent_tool"), setOf("memory_tool"))
    assertEquals(setOf("memory_tool"), scope)
  }

  @Test
  fun `resolveToolScope is empty when step expectedTools is empty`() {
    assertTrue(PlanExecutor.resolveToolScope(setOf("memory_tool", "other_tool"), emptySet()).isEmpty())
  }

  // --- isWholePlanComplete: pure, no I/O (Task 9 - ON_PLAN_COMPLETED trigger) --------------------------

  @Test
  fun `isWholePlanComplete is false for a plan with no steps`() {
    assertFalse(PlanExecutor.isWholePlanComplete(emptyList(), emptyList(), stepState(stepId = "step-1", status = StepStatus.COMPLETED)))
  }

  @Test
  fun `isWholePlanComplete is true when the just-updated step is the only step and is COMPLETED`() {
    val updated = stepState(stepId = "step-1", status = StepStatus.COMPLETED)
    assertTrue(PlanExecutor.isWholePlanComplete(listOf(step(id = "step-1")), emptyList(), updated))
  }

  @Test
  fun `isWholePlanComplete is false when another step is not COMPLETED or SKIPPED`() {
    val steps = listOf(step(id = "step-1"), step(id = "step-2"))
    val staleStates = listOf(stepState(stepId = "step-2", status = StepStatus.READY))
    val updated = stepState(stepId = "step-1", status = StepStatus.COMPLETED)
    assertFalse(PlanExecutor.isWholePlanComplete(steps, staleStates, updated))
  }

  @Test
  fun `isWholePlanComplete is true when every step is COMPLETED or SKIPPED, mixing the fresh update with prior state`() {
    val steps = listOf(step(id = "step-1"), step(id = "step-2"))
    val staleStates = listOf(stepState(stepId = "step-2", status = StepStatus.SKIPPED))
    val updated = stepState(stepId = "step-1", status = StepStatus.COMPLETED)
    assertTrue(PlanExecutor.isWholePlanComplete(steps, staleStates, updated))
  }

  @Test
  fun `isWholePlanComplete uses the updated step's status, not a stale stepStates entry for the same id`() {
    val steps = listOf(step(id = "step-1"))
    // Stale snapshot still says READY; the fresh outcome (updated) says COMPLETED - updated must win.
    val staleStates = listOf(stepState(stepId = "step-1", status = StepStatus.READY))
    val updated = stepState(stepId = "step-1", status = StepStatus.COMPLETED)
    assertTrue(PlanExecutor.isWholePlanComplete(steps, staleStates, updated))
  }

  // --- buildStepMessages: pure, no I/O ------------------------------------------------------------------

  @Test
  fun `buildStepMessages includes the step instruction, budget numbers and only same-step evidence`() {
    val s = step(id = "step-1")
    val v = version(steps = listOf(s))
    val p = plan().copy(approvedVersion = 1, status = PlanStatus.ACTIVE)
    val ownEvidence = PlanEvidence(
      id = "ev-1", planId = "plan-1", version = 1, criterionId = null, stepId = "step-1",
      source = EvidenceSource.TOOL_RESULT, reference = "ref-1", summary = "Подтверждение шага 1", createdAt = 1L,
    )
    val otherStepEvidence = PlanEvidence(
      id = "ev-2", planId = "plan-1", version = 1, criterionId = null, stepId = "step-9",
      source = EvidenceSource.TOOL_RESULT, reference = "ref-2", summary = "Чужое подтверждение другого шага", createdAt = 1L,
    )
    val snapshot = PlanSnapshot(plan = p, version = v, stepStates = emptyList(), evidence = listOf(ownEvidence, otherStepEvidence))
    val budget = PlanRunBudget(maxLlmIterationsPerStep = 4, maxToolCallsPerRun = 6, maxRetriesPerStep = 3)

    val messages = PlanExecutor.buildStepMessages("BASE_PROMPT", agent(), snapshot, s, budget, attempt = 2)

    assertEquals(2, messages.size)
    assertEquals("system", messages[0].role)
    val system = messages[0].content.orEmpty()
    assertTrue(system.contains("BASE_PROMPT"))
    assertTrue(system.contains(s.instruction))
    assertTrue("evidence for THIS step must be included", system.contains("Подтверждение шага 1"))
    assertFalse("evidence for a DIFFERENT step must not leak in", system.contains("Чужое подтверждение"))
    assertTrue(system.contains("4 витков"))
    assertTrue(system.contains("6 вызовов"))
    assertEquals("user", messages[1].role)
    assertTrue(messages[1].content.orEmpty().contains(s.id))
  }

  @Test
  fun `buildStepMessages gives the executor the FULL approved instruction, not a 220-char truncated summary`() {
    // >220 chars, with the operative rule at the very END so truncation would silently drop it.
    val longInstruction = "Сначала собери данные. " + "Делай это тщательно и по шагам. ".repeat(12) +
      "В САМОМ КОНЦЕ обязательно вызови проверку результата и только после успеха заверши шаг."
    assertTrue("test fixture must exceed the compact 220-char cap", longInstruction.length > 220)
    val s = step(id = "step-1").copy(instruction = longInstruction)
    val v = version(steps = listOf(s))
    val p = plan().copy(approvedVersion = 1, status = PlanStatus.ACTIVE)
    val snapshot = PlanSnapshot(plan = p, version = v, stepStates = emptyList(), evidence = emptyList())

    val messages = PlanExecutor.buildStepMessages("BASE_PROMPT", agent(), snapshot, s, PlanRunBudget(), attempt = 1)

    // The compact plan frame truncates to 220 chars for a summary; the executor must ALSO get the whole
    // approved instruction somewhere, or it runs a different contract than the user approved.
    val system = messages[0].content.orEmpty()
    assertTrue("the executor must see the WHOLE approved instruction, not a truncated summary", system.contains(longInstruction))
  }

  @Test
  fun `buildStepMessages folds hookInstructions into the system message without adding a message`() {
    val s = step(id = "step-1")
    val v = version(steps = listOf(s))
    val p = plan().copy(approvedVersion = 1, status = PlanStatus.ACTIVE)
    val snapshot = PlanSnapshot(plan = p, version = v, stepStates = emptyList(), evidence = emptyList())

    val withoutHook = PlanExecutor.buildStepMessages("BASE_PROMPT", agent(), snapshot, s, PlanRunBudget(), attempt = 1)
    assertFalse("no hookInstructions (default) must not add a section", withoutHook[0].content.orEmpty().contains("Дополнительные указания"))

    val withHook = PlanExecutor.buildStepMessages(
      "BASE_PROMPT", agent(), snapshot, s, PlanRunBudget(), attempt = 1, hookInstructions = listOf("EPHEMERAL_HOOK_TEXT"),
    )
    assertEquals("a hook instruction must not add a THIRD message", 2, withHook.size)
    assertTrue(withHook[0].content.orEmpty().contains("EPHEMERAL_HOOK_TEXT"))
  }

  // --- execute: preconditions refused (throw, no repository row mutated) -----------------------------

  @Test
  fun `execute throws when the plan has no approved version`() {
    val p = plan().copy(approvedVersion = null)
    val v = version()
    val snapshot = PlanSnapshot(plan = p, version = v, stepStates = emptyList(), evidence = emptyList())

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, v.steps.first(), agent()) }
    }
  }

  @Test
  fun `execute throws when the supplied snapshot is not the approved version (unapproved version refused)`() {
    // plan.approvedVersion claims v1 is approved, but the supplied snapshot itself shows v2 (e.g. a
    // pending, not-yet-approved revision) - an internally inconsistent/stale snapshot.
    val p = plan().copy(approvedVersion = 1, status = PlanStatus.ACTIVE)
    val v2 = version(version = 2)
    val snapshot = PlanSnapshot(plan = p, version = v2, stepStates = emptyList(), evidence = emptyList())

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, v2.steps.first(), agent()) }
    }
  }

  @Test
  fun `execute throws when the step does not belong to the snapshot's version`() {
    val p = plan().copy(approvedVersion = 1, status = PlanStatus.ACTIVE)
    val v = version(version = 1, steps = listOf(step(id = "step-1")))
    val snapshot = PlanSnapshot(plan = p, version = v, stepStates = emptyList(), evidence = emptyList())
    val foreignStep = step(id = "not-in-this-version")

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, foreignStep, agent()) }
    }
  }

  @Test
  fun `execute throws when the plan is PAUSED (caught by the fresh re-read, not the caller's stale snapshot)`() {
    val snapshot = runBlocking { approvedSnapshot() }
    runBlocking { repository.pause("plan-1") }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, snapshot.version.steps.first(), agent()) }
    }
  }

  @Test
  fun `execute throws when the plan is CANCELLED`() {
    val snapshot = runBlocking { approvedSnapshot() }
    runBlocking { repository.cancel("plan-1") }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, snapshot.version.steps.first(), agent()) }
    }
  }

  @Test
  fun `execute throws when the step has an incomplete dependency`() {
    val dependent = step(id = "step-2", dependsOn = setOf("step-1"))
    val snapshot = runBlocking { approvedSnapshot(steps = listOf(step(id = "step-1"), dependent)) }
    // step-1 has no PlanStepState row at all yet -> not COMPLETED/SKIPPED -> step-2 is not runnable.

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, dependent, agent()) }
    }
  }

  @Test
  fun `execute throws when the step is already COMPLETED`() {
    val snapshot = runBlocking { approvedSnapshot() }
    runBlocking { repository.updateStepState(stepState(stepId = "step-1", status = StepStatus.COMPLETED)) }

    assertThrows(IllegalStateException::class.java) {
      runBlocking { executor(FakeStepRunner(finishedResult())).execute(snapshot, snapshot.version.steps.first(), agent()) }
    }
  }

  // --- execute: budget boundaries (pre-flight refusal, not a throw) ------------------------------------

  @Test
  fun `execute refuses without running when retries are already exhausted`() = runTest {
    val snapshot = approvedSnapshot()
    repository.updateStepState(stepState(stepId = "step-1", status = StepStatus.READY, attempts = 2))
    val runner = FakeStepRunner(finishedResult())

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(), budget = PlanRunBudget(maxRetriesPerStep = 2))

    assertTrue(result.stoppedByBudget)
    assertNotNull(result.error)
    assertEquals("the loop must never even be invoked once retries are exhausted", 0, runner.callCount)
  }

  @Test
  fun `execute refuses without running when the deadline has already passed`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult())

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(), budget = PlanRunBudget(deadlineAt = now - 1))

    assertTrue(result.stoppedByBudget)
    assertEquals(0, runner.callCount)
  }

  @Test
  fun `execute blocks a retry-exhausted READY step instead of leaving it selectable (stops the heartbeat hot-loop) (C1)`() = runTest {
    val snapshot = approvedSnapshot()
    repository.updateStepState(stepState(stepId = "step-1", status = StepStatus.READY, attempts = 2))
    val runner = FakeStepRunner(finishedResult())

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(), budget = PlanRunBudget(maxRetriesPerStep = 2))

    assertTrue(result.stoppedByBudget)
    assertEquals(0, runner.callCount)
    // Left READY, the step stays selectable -> heartbeat re-arms it past-due -> immediate 0-delay
    // re-fire loop (battery + unbounded events). Block it so selectNextDue skips it and the schedule stops.
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
  }

  @Test
  fun `execute passes the step's own maxLlmIterationsPerStep and maxToolCallsPerRun through to the runner`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult())
    val budget = PlanRunBudget(maxLlmIterationsPerStep = 7, maxToolCallsPerRun = 9)

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent(), budget = budget)

    assertEquals(7, runner.lastMaxIterations)
    assertEquals(9, runner.lastMaxToolCalls)
  }

  // --- execute: tool scope end-to-end (3-way intersection narrows what the runner even sees) ----------

  @Test
  fun `execute never lets a hallucinated (out-of-agent-scope) tool reach the runner's schema list`() = runTest {
    val s = step(expectedTools = setOf("memory_tool", "tool_agent_does_not_have"))
    val snapshot = approvedSnapshot(steps = listOf(s))
    val runner = FakeStepRunner(finishedResult())

    executor(runner).execute(snapshot, s, agent(toolNames = setOf("memory_tool", "other_tool")))

    val schemaNames = runner.lastTools.orEmpty().map { it.function.name }.toSet()
    assertEquals("only the agent-AND-step intersection, never a tool the step names but the agent lacks", setOf("memory_tool"), schemaNames)
  }

  @Test
  fun `execute passes zero tool schemas when step expectedTools is empty`() = runTest {
    val s = step(expectedTools = emptySet())
    val snapshot = approvedSnapshot(steps = listOf(s))
    val runner = FakeStepRunner(finishedResult())

    executor(runner).execute(snapshot, s, agent(toolNames = setOf("memory_tool")))

    assertTrue(runner.lastTools.orEmpty().isEmpty())
  }

  // --- execute: outcome recording -----------------------------------------------------------------------

  @Test
  fun `execute records COMPLETED and completedAt on a clean finish`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult(text = "done"))

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertFalse(result.stoppedByBudget)
    assertNull(result.error)
    assertEquals("done", result.text)
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.COMPLETED, state.status)
    assertNotNull(state.completedAt)
    assertEquals(1, state.attempts)
  }

  @Test
  fun `execute records FAILED (and the error message) on an AgentState ERROR result`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(errorResult("boom"))

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertEquals("boom", result.error)
    assertFalse(result.stoppedByBudget)
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.FAILED, state.status)
    assertNull("a FAILED step never gets completedAt", state.completedAt)
  }

  @Test
  fun `execute records BLOCKED when the loop stops on hitIterationLimit`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(budgetStoppedResult(hitIterationLimit = true))

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertTrue(result.stoppedByBudget)
    assertNull("a budget cutoff is not itself an 'error'", result.error)
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
  }

  @Test
  fun `execute records BLOCKED when the loop stops on stoppedByToolBudget`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(budgetStoppedResult(stoppedByToolBudget = true))

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertTrue(result.stoppedByBudget)
    val state = requireNotNull(repository.snapshot("plan-1")).stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.BLOCKED, state.status)
  }

  // --- execute: Task 8 verification gate (PlanVerifier slotted into recordOutcome) -------------------

  @Test
  fun `execute does NOT complete when a required TOOL_RESULT criterion has no matching tool call`() = runTest {
    val criterion = toolResultCriterion()
    val snapshot = approvedSnapshot(criteria = listOf(criterion))
    val runner = FakeStepRunner(finishedResult()) // clean finish, but zero tool calls in the trace

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertFalse(result.stoppedByBudget)
    assertNull("a Retryable outcome is not itself an 'error'", result.error)
    val after = requireNotNull(repository.snapshot("plan-1"))
    val state = after.stepStates.single { it.stepId == "step-1" }
    assertEquals("an unmet required criterion must not complete the step", StepStatus.READY, state.status)
    assertNull("a step that did not complete never gets completedAt", state.completedAt)
    assertTrue("no PlanEvidence should be persisted for a criterion nothing satisfied", after.evidence.none { it.criterionId == criterion.id })

    // Task 8: "a failed required criterion leaves the step NOT completed... with an explanatory
    // evidence/event" - the STEP_VERIFIED event PlanExecutor already appends on this branch must now
    // carry a non-blank explanation of WHY, not just the bare status.
    val event = repository.getEvents("plan-1").last { it.stepId == "step-1" && it.type == PlanEventType.STEP_VERIFIED }
    val explanation = (event.payload["verification"] as? JsonPrimitive)?.content
    assertTrue("event payload must explain why verification did not complete the step", !explanation.isNullOrBlank())
  }

  @Test
  fun `execute completes and persists exactly one PlanEvidence when the required TOOL_RESULT criterion's tool succeeded`() = runTest {
    val criterion = toolResultCriterion(toolName = "memory_tool")
    val snapshot = approvedSnapshot(criteria = listOf(criterion))
    val trace = listOf(ToolTrace(iteration = 1, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Success("saved")))
    val runner = FakeStepRunner(finishedResult(toolTrace = trace))

    val result = executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertFalse(result.stoppedByBudget)
    val after = requireNotNull(repository.snapshot("plan-1"))
    val state = after.stepStates.single { it.stepId == "step-1" }
    assertEquals(StepStatus.COMPLETED, state.status)
    assertNotNull(state.completedAt)
    val matching = after.evidence.filter { it.criterionId == criterion.id }
    assertEquals("exactly one PlanEvidence for the satisfied criterion, not one per check", 1, matching.size)
    assertEquals(EvidenceSource.TOOL_RESULT, matching.single().source)
  }

  @Test
  fun `execute does NOT complete when a required TOOL_RESULT criterion's tool call errored (failed tool does not prove criterion)`() = runTest {
    val criterion = toolResultCriterion(toolName = "memory_tool")
    val snapshot = approvedSnapshot(criteria = listOf(criterion))
    val trace = listOf(ToolTrace(iteration = 1, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Error("boom")))
    val runner = FakeStepRunner(finishedResult(toolTrace = trace))

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    val after = requireNotNull(repository.snapshot("plan-1"))
    assertEquals(StepStatus.READY, after.stepStates.single { it.stepId == "step-1" }.status)
    assertTrue(after.evidence.none { it.criterionId == criterion.id })
  }

  @Test
  fun `execute completes on a clean finish when the only criteria are optional and unmet (optional criteria don't block completion)`() = runTest {
    val optional = toolResultCriterion(id = "optional-1", toolName = "never_called_tool", required = false)
    val snapshot = approvedSnapshot(criteria = listOf(optional))
    val runner = FakeStepRunner(finishedResult()) // no tool calls -> optional criterion stays unsatisfied

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    val after = requireNotNull(repository.snapshot("plan-1"))
    assertEquals(StepStatus.COMPLETED, after.stepStates.single { it.stepId == "step-1" }.status)
  }

  // --- execute: only ONE step runs per invocation ---------------------------------------------------

  @Test
  fun `execute calls the step runner exactly once, with the built system+user messages`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult())

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertEquals(1, runner.callCount)
    assertEquals(2, runner.lastMessages?.size)
  }

  @Test
  fun `execute passes interactiveApproval=false and executionMode LIVE by default (background-first)`() = runTest {
    val snapshot = approvedSnapshot()
    val runner = FakeStepRunner(finishedResult())

    executor(runner).execute(snapshot, snapshot.version.steps.first(), agent())

    assertEquals(false, runner.lastInteractiveApproval)
    assertEquals(ExecutionMode.LIVE, runner.lastExecutionMode)
  }

  // --- execute: per-agent namespace isolation (B4) --------------------------------------------------

  @Test
  fun `execute runs the step inside the plan agent's TurnContext, not a global or stale namespace`() = runTest {
    val snapshot = approvedSnapshot()
    val theAgent = agent()
    val runner = FakeStepRunner(finishedResult(), turnContextHolder)

    executor(runner).execute(snapshot, snapshot.version.steps.first(), theAgent)

    // Memory/file/storage tools resolve their namespace from TurnContextHolder. A plan step that runs
    // WITHOUT wrapping in the plan agent's context leaks into the global/stale namespace of whatever
    // last used this dispatcher thread — cross-agent read/write. The step must run inside exactly this
    // plan's agent + conversation.
    assertEquals(theAgent, runner.observedAgent)
    assertEquals("conv-1", runner.observedConversationId)
  }
}
