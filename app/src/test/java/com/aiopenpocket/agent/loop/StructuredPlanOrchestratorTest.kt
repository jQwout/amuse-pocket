package com.aiopenpocket.agent.loop

// @trace structured-plan-engine-907066

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.plan.execution.MemoryFactLookup
import com.aiopenpocket.agent.core.plan.execution.PlanEvidenceSink
import com.aiopenpocket.agent.core.plan.execution.PlanExecutor
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.plan.execution.PlanRunner
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.execution.PlanStepRunner
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.planner.PlanControlSchemas
import com.aiopenpocket.agent.core.plan.planner.PlanPlanner
import com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder
import com.aiopenpocket.agent.core.plan.template.PlanTemplate
import com.aiopenpocket.agent.core.plan.template.PlanTemplateRegistry
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [StructuredPlanOrchestrator] (Task 10) round-tripped through REAL (in-memory) Room
 * [PlanRepository]/[AgentHookRepository]/[ConversationRepository] and a REAL [PlanPlanner]/
 * [PlanRunner]/[PlanExecutor]/[PlanVerifier] chain — a [FakeLlmClient] (mirrors
 * [PlanPlannerTest]'s own harness) stands in for the planning turn's model, and a
 * [ScriptedStepRunner] (mirrors [PlanExecutorTest]'s own `FakeStepRunner`, via the same
 * [PlanStepRunner] seam) stands in for the GOAL step turn's model. Nothing here mocks
 * [StructuredPlanOrchestrator]'s own collaborators individually — the point of this suite is that
 * the WHOLE chain (persist proposal, fire ON_PLAN_CREATED, emit the right terminal [AgentEvent],
 * delegate GOAL to [PlanRunner]) works end to end, the same integration posture
 * [PlanHookIntegrationTest]/[PlanApprovalTest] already use for their own slice of the engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StructuredPlanOrchestratorTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: PlanRepository
  private lateinit var hookRepository: AgentHookRepository
  private lateinit var conversationRepository: ConversationRepository
  private lateinit var toolRegistry: ToolRegistry
  private lateinit var stepRunner: ScriptedStepRunner
  private lateinit var llm: FakeLlmClient
  private lateinit var recordingSync: RecordingScheduleSync
  private var now = 10_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = PlanRepository(db, db.planDao()) { now }
    hookRepository = AgentHookRepository(db.agentHookDao())
    conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool")))
    stepRunner = ScriptedStepRunner()
    recordingSync = RecordingScheduleSync()
    llm = FakeLlmClient(textThenCall("Вот план.", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()))
  }

  @After
  fun tearDown() = db.close()

  // --- fakes (mirror PlanPlannerTest's / PlanExecutorTest's own) ---------------------------------

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

  private class FakeLlmClient(private var events: List<StreamEvent>) : LlmClient {
    var lastTools: List<FunctionSchema>? = null
      private set

    fun respondWith(events: List<StreamEvent>) {
      this.events = events
    }

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      lastTools = tools
      return flowOf(*events.toTypedArray())
    }
  }

  private class FakeAgentResolver(private val agents: Map<String, AgentDefinition>) : AgentResolver {
    override suspend fun resolve(id: String?): AgentDefinition? = id?.let { agents[it] }
    override fun observeRoster(): Flow<List<AgentDefinition>> = flowOf(agents.values.toList())
  }

  private class FakeTemplateRegistry(private val templates: Map<String, PlanTemplate> = emptyMap()) : PlanTemplateRegistry {
    override suspend fun getForAgent(agentId: String?): PlanTemplate? = agentId?.let { templates[it] }
  }

  private class RecordingScheduleSync : PlanScheduleSync {
    val syncedPlanIds = mutableListOf<String>()
    val cancelledPlanIds = mutableListOf<String>()
    override suspend fun sync(planId: String) { syncedPlanIds += planId }
    override suspend fun cancel(planId: String) { cancelledPlanIds += planId }
  }

  /** Mirrors [PlanExecutorTest]'s own `FakeStepRunner`/[PlanRunnerTest]'s `ScriptedStepRunner`. */
  private class ScriptedStepRunner : PlanStepRunner {
    var script: () -> AgentResult = { AgentResult(text = "ok", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 1) }
    var callCount = 0
      private set
    var lastExecutionMode: ExecutionMode? = null
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
      lastExecutionMode = executionMode
      return script()
    }
  }

  // --- JSON argument builders (mirror PlanPlannerTest's own field names exactly) -----------------

  private fun stepJson(id: String, expectedTools: List<String> = emptyList()): JsonObject = JsonObject(
    mapOf(
      "id" to JsonPrimitive(id),
      "title" to JsonPrimitive("Title $id"),
      "instruction" to JsonPrimitive("Do $id"),
      "dependsOn" to JsonArray(emptyList()),
      "expectedTools" to JsonArray(expectedTools.map { JsonPrimitive(it) }),
      "completionRule" to JsonObject(mapOf("type" to JsonPrimitive("TOOL_SUCCEEDED"))),
    ),
  )

  private fun submitPlanArgs(
    objective: String = "objective",
    steps: List<JsonObject> = listOf(stepJson("s1")),
  ): Map<String, JsonElement> = mapOf(
    "objective" to JsonPrimitive(objective),
    "constraints" to JsonArray(emptyList()),
    "steps" to JsonArray(steps),
    "criteria" to JsonArray(emptyList()),
  )

  private fun textThenCall(text: String, callName: String, args: Map<String, JsonElement>) = listOf(
    StreamEvent.TextDelta(text),
    StreamEvent.ToolCall("call-1", callName, args),
    StreamEvent.Finish("tool_calls"),
  )

  // --- fixtures ------------------------------------------------------------------------------------

  private fun agentDefinition(toolNames: Set<String> = setOf("memory_tool")) = AgentDefinition(
    id = "agent-1", source = AgentSource.USER, name = "Тестовый агент",
    description = "Тестовый агент для StructuredPlanOrchestratorTest.", iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.", toolNames = toolNames, skillIds = emptySet(),
    defaultMode = AgentMode.GOAL, allowedModes = setOf(AgentMode.PLAN, AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:agent-1:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(), hooks = emptyList(), configVersion = 1L, status = AgentStatus.PUBLISHED,
  )

  private fun hook(id: String, event: HookEvent, actionType: HookActionType = HookActionType.LOG, agentId: String = "agent-1") = AgentHook(
    id = id, agentId = agentId, event = event, conditionType = "always", conditionJson = "{}",
    actionType = actionType, actionJson = "{}", priority = 0, enabled = true,
  )

  private fun verifier() = PlanVerifier(PlanEvidenceSink(repository::addEvidence), NoMemoryFacts, UnusedLlmClient, NoAssets) { now }

  private fun executor() = PlanExecutor(repository, toolRegistry, stepRunner, NoAssets, verifier(), hookRepository, TurnContextHolder()) { now }

  private fun orchestrator(
    agents: Map<String, AgentDefinition> = mapOf("agent-1" to agentDefinition()),
    templates: Map<String, PlanTemplate> = emptyMap(),
  ): StructuredPlanOrchestrator {
    val agentResolver = FakeAgentResolver(agents)
    val planner = PlanPlanner(llm, agentResolver, FakeTemplateRegistry(templates), PlanPromptBuilder(NoAssets))
    val planRunner = PlanRunner(repository, executor(), agentResolver) { now }
    return StructuredPlanOrchestrator(planner, planRunner, repository, conversationRepository, FakeTemplateRegistry(templates), hookRepository, recordingSync, NoopLog) { now }
  }

  private fun programTemplate(agentId: String = "agent-1") = PlanTemplate(
    id = "t-program", agentId = agentId, supportedHorizons = setOf(PlanHorizon.PROGRAM),
    requiredConstraints = emptyList(), suggestedSteps = emptyList(), suggestedCriteria = emptyList(), forbiddenToolNames = emptySet(),
  )

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  // --- PLAN: propose ------------------------------------------------------------------------------

  @Test
  fun `propose persists a brand-new proposal, fires ON_PLAN_CREATED and emits PlanProposed`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    hookRepository.replaceForAgent("agent-1", listOf(hook("h-created", HookEvent.ON_PLAN_CREATED)))

    val events = orchestrator().propose(convId, "распланируй неделю").toList()

    val proposed = events.filterIsInstance<AgentEvent.PlanProposed>().single()
    assertEquals(1, proposed.version)
    val snapshot = repository.snapshot(proposed.planId)!!
    assertEquals(PlanStatus.AWAITING_APPROVAL, snapshot.plan.status)
    assertEquals(1, snapshot.version.version)
    assertTrue("ON_PLAN_CREATED must fire for a brand-new plan", hookRepository.getRunsForHook("h-created").isNotEmpty())
    // The planning turn must never see a real action-tool schema, only the 3 control schemas.
    assertEquals(PlanControlSchemas.NAMES, llm.lastTools?.map { it.function.name }?.toSet())
  }

  @Test
  fun `a second propose call while the first is still unapproved revises the SAME plan and does not re-fire ON_PLAN_CREATED`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    hookRepository.replaceForAgent("agent-1", listOf(hook("h-created", HookEvent.ON_PLAN_CREATED)))
    val org = orchestrator()

    val first = org.propose(convId, "распланируй неделю").toList().filterIsInstance<AgentEvent.PlanProposed>().single()
    llm.respondWith(textThenCall("Уточнённый план.", PlanControlSchemas.PROPOSE_PLAN_REVISION, submitPlanArgs(objective = "objective v2")))
    val second = org.propose(convId, "добавь бюджет").toList().filterIsInstance<AgentEvent.PlanProposed>().single()

    assertEquals(first.planId, second.planId)
    assertEquals(2, second.version)
    assertEquals(1, hookRepository.getRunsForHook("h-created").size)
  }

  @Test
  fun `propose surfaces request_plan_information as PlanBlocked without persisting anything`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    llm.respondWith(
      listOf(
        StreamEvent.ToolCall("call-1", PlanControlSchemas.REQUEST_PLAN_INFORMATION, mapOf("question" to JsonPrimitive("Какой бюджет?"))),
        StreamEvent.Finish("tool_calls"),
      ),
    )

    val events = orchestrator().propose(convId, "распланируй").toList()

    val blocked = events.filterIsInstance<AgentEvent.PlanBlocked>().single()
    assertEquals("Какой бюджет?", blocked.reason)
    assertTrue(events.none { it is AgentEvent.PlanProposed })
    assertTrue("nothing should be persisted for a NeedsInfo turn", repository.observeByConversation(convId).first().isEmpty())
  }

  @Test
  fun `propose surfaces a validator rejection as AgentEvent Error`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    // agent-1 only has "memory_tool" — a step expecting a tool outside that scope is rejected by
    // PlanValidator ("tool_outside_agent_scope"), never silently coerced.
    llm.respondWith(textThenCall("Вот план.", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs(steps = listOf(stepJson("s1", expectedTools = listOf("tool_agent_does_not_have"))))))

    val events = orchestrator().propose(convId, "распланируй").toList()

    assertTrue(events.any { it is AgentEvent.Error })
    assertTrue(events.none { it is AgentEvent.PlanProposed })
  }

  @Test
  fun `propose surfaces a text-only planner response (no control call) as AgentEvent Error`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    llm.respondWith(listOf(StreamEvent.TextDelta("Sure, here's a plan..."), StreamEvent.Finish("stop")))

    val events = orchestrator().propose(convId, "распланируй").toList()

    assertTrue(events.any { it is AgentEvent.Error })
  }

  // --- GOAL: executeGoal ---------------------------------------------------------------------------

  @Test
  fun `executeGoal falls back to propose when the conversation has no plan yet`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")

    val events = orchestrator().executeGoal(convId, planId = null, userMessage = "начни", provider = LlmProvider.NIM).toList()

    assertTrue("no plan existed yet, so GOAL must fall back to a proposal", events.any { it is AgentEvent.PlanProposed })
    assertEquals(0, stepRunner.callCount)
  }

  @Test
  fun `executeGoal on an ACTIVE plan runs one step via PlanRunner and emits PlanProgressed`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    val org = orchestrator()
    org.propose(convId, "распланируй").toList()
    val planId = repository.observeByConversation(convId).first().single().plan.id
    repository.approve(planId, 1)
    stepRunner.script = { AgentResult(text = "ok", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 1) }

    val events = org.executeGoal(convId, planId = null, userMessage = "продолжай", provider = LlmProvider.NIM).toList()

    val progressed = events.filterIsInstance<AgentEvent.PlanProgressed>().single()
    assertEquals(PlanRunOutcome.COMPLETED, progressed.result.outcome) // single criteria-less step -> trivially COMPLETED
    assertEquals(1, stepRunner.callCount)
    assertEquals(PlanStatus.COMPLETED, repository.snapshot(planId)!!.plan.status)
  }

  @Test
  fun `executeGoal in PREVIEW threads PREVIEW mode all the way to the step runner (builder test-run has no side effects)`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    val org = orchestrator()
    org.propose(convId, "распланируй").toList()
    val planId = repository.observeByConversation(convId).first().single().plan.id
    repository.approve(planId, 1)
    stepRunner.script = { AgentResult(text = "ok", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 1) }

    org.executeGoal(convId, planId = null, userMessage = "продолжай", provider = LlmProvider.NIM, executionMode = ExecutionMode.PREVIEW).toList()

    // A GOAL turn started from the builder's Preview must reach ExecutionPolicy as PREVIEW so WRITE/
    // EXTERNAL tools are simulated, not run. If any link (executeGoal -> PlanRunRequest -> PlanRunner
    // -> PlanExecutor.execute) drops the flag, the step runs LIVE and the "no side effects" promise breaks.
    assertEquals(ExecutionMode.PREVIEW, stepRunner.lastExecutionMode)
  }

  @Test
  fun `executeGoal re-syncs an ACTIVE PROGRAM plan's schedule after a foreground run (C3)`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    val org = orchestrator(templates = mapOf("agent-1" to programTemplate()))
    // Two independent steps + a 1-step budget: one runs, the PROGRAM plan stays ACTIVE (step 2 still
    // pending), so it still needs its heartbeat schedule. A heartbeat racing this foreground run cancels
    // that schedule; nothing re-arms it unless executeGoal re-syncs after the run.
    llm.respondWith(textThenCall("План.", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs(steps = listOf(stepJson("s1"), stepJson("s2")))))
    org.propose(convId, "распланируй").toList()
    val planId = repository.observeByConversation(convId).first().single().plan.id
    repository.approve(planId, 1)
    stepRunner.script = { AgentResult(text = "ok", toolTrace = emptyList(), state = AgentState.FINISHED, iterations = 1) }

    org.executeGoal(convId, planId = null, userMessage = "продолжай", provider = LlmProvider.NIM, budget = PlanRunBudget(maxStepsPerRun = 1)).toList()

    assertEquals(PlanStatus.ACTIVE, repository.snapshot(planId)!!.plan.status)
    assertTrue(
      "a PROGRAM plan still ACTIVE after a foreground run must be rescheduled (heartbeat-collision recovery)",
      recordingSync.syncedPlanIds.contains(planId),
    )
  }

  @Test
  fun `executeGoal reports a FAILED PlanRunner outcome as AgentEvent Error, not PlanProgressed`() = runTest {
    val convId = conversationRepository.createConversation(title = "Test", agentId = "agent-1")
    val org = orchestrator()
    org.propose(convId, "распланируй").toList()
    val planId = repository.observeByConversation(convId).first().single().plan.id
    repository.approve(planId, 1)
    stepRunner.script = { AgentResult(text = "", toolTrace = emptyList(), state = AgentState.ERROR, iterations = 1, errorMessage = "boom") }

    val events = org.executeGoal(convId, planId = null, userMessage = "продолжай", provider = LlmProvider.NIM).toList()

    assertTrue(events.any { it is AgentEvent.Error })
    assertTrue(events.none { it is AgentEvent.PlanProgressed })
  }
}
