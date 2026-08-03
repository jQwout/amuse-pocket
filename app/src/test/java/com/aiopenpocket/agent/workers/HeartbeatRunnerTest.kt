package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
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
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.execution.MemoryFactLookup
import com.aiopenpocket.agent.core.plan.execution.PlanEvidenceSink
import com.aiopenpocket.agent.core.plan.execution.PlanExecutor
import com.aiopenpocket.agent.core.plan.execution.PlanRunner
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.execution.PlanStepRunner
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatRunnerTest {

  /** Что «выбрано в Настройках» на момент фонового хода. */
  private var selectedProvider = LlmProvider.NIM

  private class FakeLlm(private val events: List<StreamEvent>) : LlmClient {
    /** Snapshot (not a live reference) of `messages` as seen on each `streamChat` call — lets a test
     * assert on the actual system-prompt content sent, without risking a later in-place mutation of
     * the caller's mutable list retroactively changing what a prior call "saw". */
    val calls = mutableListOf<List<ChatMessage>>()

    /** К какому вендору ушёл фоновый ход. */
    val providers = mutableListOf<LlmProvider>()

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      calls += messages.toList()
      providers += provider
      return flowOf(*events.toTypedArray())
    }
  }

  /** Test-only [Tool] that counts real invocations — the direct way to prove a tool scoped OUT of a
   * background run (Task 22's toolNames ∩ SAFE_TOOL_NAMES intersection, or an approval-required hook
   * denied by `interactiveApproval = false`) never actually executes, rather than inferring it from
   * indirect side effects. */
  private class CountingTool(override val name: String) : Tool {
    var callCount = 0
      private set
    override val description = "Test tool '$name'."
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
      callCount++
      return ToolResult.Success("ok")
    }
  }

  private class FakeNotifier : TurnNotifier {
    data class Call(val conversationId: String, val title: String?, val preview: String, val failed: Boolean)
    val calls = mutableListOf<Call>()
    override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
      calls += Call(conversationId, conversationTitle, preview, failed)
    }
  }

  // --- Task 11: typed plan-step heartbeat doubles -------------------------------------------------

  /** Records every sync/cancel call instead of touching real WorkManager scheduling — mirrors
   * `PlanApprovalTest`'s own `RecordingPlanScheduleSync`. */
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

  private object NoPlanAssets : AssetSource {
    override fun load(path: String): String? = null
  }

  private object NoMemoryFacts : MemoryFactLookup {
    override suspend fun getFactsByKeyPrefix(prefix: String): List<Fact> = emptyList()
  }

  /** Every fixture plan in this file has zero criteria, so `PlanVerifier` never reaches its
   * MODEL_ASSESSMENT branch — mirrors `PlanRunnerTest`'s own `UnusedLlmClient` (a real call would be a
   * test-setup bug, not something to silently tolerate). */
  private object UnusedPlanLlmClient : LlmClient {
    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> =
      throw IllegalStateException("LlmClient must not be called: no MODEL_ASSESSMENT criterion in this file.")
  }

  /** Bundles a [HeartbeatRunner] with the [FakeLlm] double it was wired to, for tests that need to
   * inspect what actually reached the "model" (e.g. the system prompt) — [runner] itself only
   * returns the former, which is all the pre-Task-22 tests ever needed. [planRepository]/
   * [planScheduleSync] (Task 11) let a plan-step test set up/approve a plan and assert on
   * sync/cancel calls without each test re-wiring its own copies. */
  private data class Doubles(
    val runner: HeartbeatRunner,
    val llm: FakeLlm,
    val planRepository: PlanRepository,
    val planScheduleSync: RecordingPlanScheduleSync,
  )

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var notifier: FakeNotifier
  private lateinit var userAgentRepository: UserAgentRepository

  @Before
  fun setUp() = runTest {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    notifier = FakeNotifier()
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))
    // FK: MessageEntity.conversationId -> ConversationEntity.id. Диалог должен существовать ДО
    // того, как ConversationRepository.addMessage попытается в него что-то записать.
    db.conversationDao().insert(ConversationEntity(id = "conv1", title = "T"))
  }

  @After
  fun tearDown() = db.close()

  /** Same wiring [runner] uses, but with an explicit [toolRegistry] (Task 22 tests need real tool
   * doubles registered to prove scoping/approval-denial by actual call count), an optional shared
   * [turnContextHolder] (so a tool double can read what [HeartbeatRunner] wrapped the run in), and
   * returning the [FakeLlm] alongside the runner so a test can inspect what it was called with. */
  private fun runnerWithRegistry(
    events: List<StreamEvent>,
    toolRegistry: ToolRegistry,
    turnContextHolder: TurnContextHolder = TurnContextHolder(),
  ): Doubles {
    val fakeLlm = FakeLlm(events)
    val reactLoopRunner = ReactLoopRunner(
      fakeLlm,
      ToolExecutor(toolRegistry),
      toolRegistry,
      MemoryRepository(db.factDao(), db.episodeDao()),
      NoopAgentLog,
      AgentHookRepository(db.agentHookDao()),
      ToolApprovalCoordinator(),
    )
    val conversationRepository = ConversationRepository(
      db.conversationDao(),
      db.conversationTagDao(),
      db.messageDao(),
    )
    val agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao())))
    // Task 11: the SAME reactLoopRunner (and thus the SAME fakeLlm/events script) backs a typed
    // plan-step run too — production's own PlanStepRunnerBindings is nothing more than
    // `PlanStepRunner(reactLoopRunner::run)`, so a plan-step test scripts `events` exactly like a
    // generic agent-mode test does, and gets the REAL ToolExecutor/HookEngine/ExecutionPolicy
    // machinery underneath (not a step-runner fake that would bypass hook enforcement entirely).
    val planRepository = PlanRepository(db, db.planDao())
    val planExecutor = PlanExecutor(
      planRepository,
      toolRegistry,
      PlanStepRunner(reactLoopRunner::run),
      NoPlanAssets,
      PlanVerifier(PlanEvidenceSink(planRepository::addEvidence), NoMemoryFacts, UnusedPlanLlmClient, NoPlanAssets),
      AgentHookRepository(db.agentHookDao()),
      turnContextHolder,
    )
    val planRunner = PlanRunner(planRepository, planExecutor, agentResolver)
    val planScheduleSync = RecordingPlanScheduleSync()
    val runner = HeartbeatRunner(
      db.heartbeatDao(),
      reactLoopRunner,
      toolRegistry,
      conversationRepository,
      notifier,
      agentResolver,
      turnContextHolder,
      planRepository,
      planRunner,
      planScheduleSync,
      { selectedProvider },
    )
    return Doubles(runner, fakeLlm, planRepository, planScheduleSync)
  }

  private fun runner(events: List<StreamEvent>): HeartbeatRunner =
    runnerWithRegistry(events, ToolRegistry(emptySet())).runner

  // --- Task 11: typed plan-step fixtures (mirrors PlanRunnerTest's own builders, trimmed) ---------

  private fun plan(
    id: String = "plan-1",
    conversationId: String = "conv1",
    agentId: String? = null,
    status: PlanStatus = PlanStatus.DRAFT,
    approvedVersion: Int? = null,
  ) = AgentPlan(
    id = id, agentId = agentId, conversationId = conversationId, horizon = PlanHorizon.PROGRAM,
    status = status, approvedVersion = approvedVersion, activeStepId = null,
    createdAt = 1_000L, updatedAt = 1_000L,
  )

  private fun planStep(id: String = "step-1", expectedTools: Set<String> = emptySet()) = PlanStep(
    id = id, title = "Title $id", instruction = "Instruction $id",
    dependsOn = emptySet(), expectedTools = expectedTools, schedule = null,  )

  private fun planVersion(planId: String = "plan-1", version: Int = 1, steps: List<PlanStep> = listOf(planStep())) = PlanVersion(
    planId = planId, version = version, objective = "Objective", assumptions = emptyList(),
    constraints = emptyList(), steps = steps, criteria = emptyList(), createdBy = PlanAuthor.AGENT, approvedAt = null,
  )

  /** A typed [HeartbeatTargetType.PLAN_STEP] row, ready to insert and fire via [HeartbeatRunner.handleFire]. */
  private fun planHeartbeat(
    id: String = "hb1",
    planId: String = "plan-1",
    planVersion: Int = 1,
    planStepId: String = "step-1",
    conversationId: String = "conv1",
    agentId: String? = null,
  ) = HeartbeatEntity(
    id = id, title = "Шаг плана", mode = "agent", conversationId = conversationId, kind = "one_time",
    status = "scheduled", agentId = agentId,
    targetType = HeartbeatTargetType.PLAN_STEP.name, planId = planId, planVersion = planVersion, planStepId = planStepId,
  )

  private fun hb(mode: String, prompt: String? = null, kind: String = "one_time") = HeartbeatEntity(
    id = "hb1", title = "T", mode = mode, prompt = prompt, message = "msg",
    conversationId = "conv1", kind = kind, intervalMinutes = if (kind == "periodic") 30 else null,
  )

  /** Unpublished (DRAFT) [AgentSource.USER] draft, same shape [AgentResolverTest] uses — publish it
   * (optionally with [hooks]) via [publishCustomAgent] to get a real, resolvable custom agent. */
  private fun customAgentDraft(id: String, toolNames: Set<String> = setOf("memory_tool")) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый кастомный агент",
    description = "Кастомный агент для проверки heartbeat.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый кастомный агент для heartbeat-теста.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    configVersion = 0L,
    status = AgentStatus.DRAFT,
  )

  /** Saves then publishes a [customAgentDraft] under [id], returning the PUBLISHED snapshot. */
  private suspend fun publishCustomAgent(
    id: String,
    toolNames: Set<String> = setOf("memory_tool"),
    hooks: List<AgentHook> = emptyList(),
  ): AgentDefinition {
    userAgentRepository.saveDraft(customAgentDraft(id, toolNames))
    return userAgentRepository.publish(id, hooks = hooks)
  }

  private object NoopAgentLog : com.aiopenpocket.agent.core.AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  @Test
  fun `unknown or cancelled heartbeat is a no-op DONE`() = runTest {
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("nope", attempt = 0))
  }

  @Test
  fun `push mode shows notification and records fired`() = runTest {
    db.heartbeatDao().insert(hb(mode = "push"))
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    // Пуш ушёл через общий нотифаер, с заголовком напоминания и deep-link в его диалог.
    assertEquals("T", notifier.calls.single().title)
    assertEquals("conv1", notifier.calls.single().conversationId)
  }

  @Test
  fun `agent mode with prompt writes assistant message to conversation`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь задачи"))
    val runner = runner(listOf(StreamEvent.TextDelta("Всё в порядке, задач 3."), StreamEvent.Finish("stop")))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    val messages = db.messageDao().getAllOrdered("conv1")
    assertTrue(messages.any { it.role == "assistant" && it.content.contains("задач 3") })
    assertEquals("Всё в порядке, задач 3.", db.heartbeatDao().getById("hb1")?.lastResult)
    assertTrue(notifier.calls.single().preview.contains("задач 3")) // ответ ушёл и пушем
  }

  /**
   * Фоновый ход обязан идти к тому же вендору, что и обычный. Раньше здесь стоял литерал
   * LlmProvider.NIM: после переключения на OpenRouter напоминалки продолжали бы ходить к NVIDIA —
   * то есть тихо падать на пустом ключе или уносить данные вендору, от которого пользователь ушёл.
   */
  @Test
  fun `agent mode goes to the provider selected in settings`() = runTest {
    selectedProvider = LlmProvider.OPENROUTER
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь задачи"))
    val doubles = runnerWithRegistry(
      listOf(StreamEvent.TextDelta("ок"), StreamEvent.Finish("stop")),
      ToolRegistry(emptySet()),
    )
    assertEquals(FireOutcome.DONE, doubles.runner.handleFire("hb1", attempt = 0))
    assertEquals(listOf(LlmProvider.OPENROUTER), doubles.llm.providers)
  }

  @Test
  fun `pulse HEARTBEAT_OK stays silent - no message`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = null))
    val runner = runner(listOf(StreamEvent.TextDelta("HEARTBEAT_OK"), StreamEvent.Finish("stop")))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertTrue(db.messageDao().getAllOrdered("conv1").isEmpty())
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(notifier.calls.isEmpty()) // HEARTBEAT_OK = полная тишина, пуша тоже нет
  }

  @Test
  fun `llm error retries then marks error`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x"))
    val runner = runner(listOf(StreamEvent.Error("network down")))
    assertEquals(FireOutcome.RETRY, runner.handleFire("hb1", attempt = 0))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 2))
    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
  }

  @Test
  fun `periodic keeps status scheduled after successful run`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x", kind = "periodic"))
    val runner = runner(listOf(StreamEvent.TextDelta("Готово"), StreamEvent.Finish("stop")))
    runner.handleFire("hb1", attempt = 0)
    assertEquals("scheduled", db.heartbeatDao().getById("hb1")?.status)
  }

  // --- Task 22: custom agents in the background heartbeat ----------------------------------------

  @Test
  fun `published custom agent runs and writes its answer to the conversation`() = runTest {
    val custom = publishCustomAgent("custom-1")
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь задачи").copy(agentId = custom.id))
    val runner = runner(listOf(StreamEvent.TextDelta("Всё в порядке, задач 3."), StreamEvent.Finish("stop")))

    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))

    val messages = db.messageDao().getAllOrdered("conv1")
    assertTrue(messages.any { it.role == "assistant" && it.content.contains("задач 3") })
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(notifier.calls.single().preview.contains("задач 3"))
  }

  @Test
  fun `custom agent's own system prompt reaches the LLM as a frozen config snapshot`() = runTest {
    val custom = publishCustomAgent("snapshot-agent")
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "статус").copy(agentId = custom.id))
    val (runner, llm) = runnerWithRegistry(
      listOf(StreamEvent.TextDelta("Готово"), StreamEvent.Finish("stop")),
      ToolRegistry(emptySet()),
    )

    runner.handleFire("hb1", attempt = 0)

    // Exactly one streamChat call (no tool call in this script) — its system message must carry
    // THIS agent's own systemPrompt, proving the resolved AgentDefinition snapshot (not a generic
    // fallback, and not a SECOND re-resolve) is what actually reached the loop.
    val systemMessage = llm.calls.single().first()
    assertEquals("system", systemMessage.role)
    assertTrue(systemMessage.content!!.contains(custom.systemPrompt))
  }

  @Test
  fun `heartbeat bound to an archived custom agent is skipped, not executed`() = runTest {
    val archived = publishCustomAgent("archived-agent")
    userAgentRepository.archive(archived.id)
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь").copy(agentId = archived.id))
    val runner = runner(listOf(StreamEvent.TextDelta("не должно попасть в чат"), StreamEvent.Finish("stop")))

    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))

    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(db.messageDao().getAllOrdered("conv1").isEmpty())
    assertTrue(notifier.calls.isEmpty())
  }

  @Test
  fun `heartbeat bound to an unknown agent id is skipped, not executed`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь").copy(agentId = "does-not-exist"))
    val runner = runner(listOf(StreamEvent.TextDelta("не должно попасть в чат"), StreamEvent.Finish("stop")))

    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))

    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(db.messageDao().getAllOrdered("conv1").isEmpty())
    assertTrue(notifier.calls.isEmpty())
  }

  @Test
  fun `narrow custom agent gets only the tool intersection, not every safe tool`() = runTest {
    // "memory_tool" is BOTH in SAFE_TOOL_NAMES and this agent's own toolNames -> must run for real.
    // "notifications" is in SAFE_TOOL_NAMES but NOT in this agent's toolNames -> must be rejected
    // before ToolExecutor ever sees it (the exact regression Task 22 fixes: previously EVERY agent,
    // however narrowly scoped, got the full SAFE_TOOL_NAMES set regardless of its own toolNames).
    val memoryTool = CountingTool("memory_tool")
    val notificationsTool = CountingTool("notifications")
    val registry = ToolRegistry(setOf(memoryTool, notificationsTool))
    val custom = publishCustomAgent("scoped-agent", toolNames = setOf("memory_tool"))
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь").copy(agentId = custom.id))
    val events = listOf(
      StreamEvent.ToolCall(id = "1", name = "memory_tool", arguments = emptyMap()),
      StreamEvent.ToolCall(id = "2", name = "notifications", arguments = emptyMap()),
      StreamEvent.Finish("stop"),
    )
    val (runner, _) = runnerWithRegistry(events, registry)

    runner.handleFire("hb1", attempt = 0)

    assertTrue("in-scope safe tool must actually execute", memoryTool.callCount >= 1)
    assertEquals("out-of-scope safe tool must never execute", 0, notificationsTool.callCount)
  }

  @Test
  fun `background run wraps TurnContextHolder with the bound agent, not the shared namespace`() = runTest {
    // Direct proof of the Task 22 "deferred leak fix": a tool executed mid-run reads BACK
    // turnContextHolder.agentId/conversationId — if HeartbeatRunner failed to wrap reactLoopRunner.run
    // in withContext(turnContextHolder.contextElement(...)), this tool would see whatever this thread's
    // ThreadLocal happened to hold from a PRIOR run (or nothing), not THIS heartbeat's own agent.
    val holder = TurnContextHolder()
    var seenAgentId: String? = "unset-before-tool-ran"
    var seenConversationId: String? = "unset-before-tool-ran"
    val namespaceProbeTool = object : Tool {
      override val name = "memory_tool"
      override val description = "Probe tool reading TurnContextHolder mid-run."
      override val schema = toolSchema(name, description) {}
      override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        seenAgentId = holder.agentId
        seenConversationId = holder.conversationId
        return ToolResult.Success("ok")
      }
    }
    val registry = ToolRegistry(setOf(namespaceProbeTool))
    val custom = publishCustomAgent("namespace-agent")
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь").copy(agentId = custom.id))
    val events = listOf(
      StreamEvent.ToolCall(id = "1", name = "memory_tool", arguments = emptyMap()),
      StreamEvent.Finish("stop"),
    )
    val (runner, _) = runnerWithRegistry(events, registry, holder)

    runner.handleFire("hb1", attempt = 0)

    assertEquals(custom.id, seenAgentId)
    assertEquals("conv1", seenConversationId)
  }

  @Test
  fun `background run denies an approval-required tool instead of executing it`() = runTest {
    val guardedTool = CountingTool("memory_tool")
    val registry = ToolRegistry(setOf(guardedTool))
    val requireApprovalHook = AgentHook(
      id = "hook-1",
      agentId = "hook-agent",
      event = HookEvent.BEFORE_TOOL,
      conditionType = "always",
      conditionJson = "",
      actionType = HookActionType.REQUIRE_APPROVAL,
      actionJson = "",
      priority = 0,
      enabled = true,
    )
    publishCustomAgent("hook-agent", toolNames = setOf("memory_tool"), hooks = listOf(requireApprovalHook))
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь").copy(agentId = "hook-agent"))
    val events = listOf(
      StreamEvent.ToolCall(id = "1", name = "memory_tool", arguments = emptyMap()),
      StreamEvent.Finish("stop"),
    )
    val (runner, _) = runnerWithRegistry(events, registry)

    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))

    // interactiveApproval=false (Task 9) must turn the hook's REQUIRE_APPROVAL into an immediate
    // deny — no screen exists in the background to ever approve it — so the tool never actually runs.
    assertEquals(0, guardedTool.callCount)
  }

  // --- Task 11: typed plan-step heartbeats --------------------------------------------------------

  @Test
  fun `typed plan-step heartbeat routes to PlanRunner and runs exactly that step, not a free-text turn`() = runTest {
    val (runner, llm, planRepository, planScheduleSync) = runnerWithRegistry(
      listOf(StreamEvent.Finish("stop")),
      ToolRegistry(emptySet()),
    )
    planRepository.saveProposal(plan(id = "plan-1"), planVersion(planId = "plan-1", steps = listOf(planStep(id = "step-1"))))
    planRepository.approve("plan-1", 1)
    db.heartbeatDao().insert(planHeartbeat())

    val outcome = runner.handleFire("hb1", attempt = 0)

    assertEquals(FireOutcome.DONE, outcome)
    // PlanExecutor.buildStepMessages' own last line is "Выполни шаг [step-1] «...» сейчас." — proof
    // this went through the plan-step prompt, never fireAgent's PULSE_PROMPT/task-based framing.
    val userMessage = llm.calls.single().last { it.role == "user" }
    assertTrue(userMessage.content!!.contains("[step-1]"))
    assertEquals(PlanStatus.COMPLETED, planRepository.snapshot("plan-1")!!.plan.status)
    assertEquals(listOf("plan-1"), planScheduleSync.cancelled) // terminal outcome -> scheduler torn down
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
  }

  @Test
  fun `typed plan-step heartbeat never blocks on approval - a BEFORE_PLAN_STEP REQUIRE_APPROVAL hook resolves to BLOCKED instead of waiting`() = runTest {
    val requireApprovalHook = AgentHook(
      id = "hook-1",
      agentId = "plan-agent",
      event = HookEvent.BEFORE_PLAN_STEP,
      conditionType = "always",
      conditionJson = "{}",
      actionType = HookActionType.REQUIRE_APPROVAL,
      actionJson = "{}",
      priority = 0,
      enabled = true,
    )
    val agent = publishCustomAgent("plan-agent", hooks = listOf(requireApprovalHook))
    val (runner, llm, planRepository, planScheduleSync) = runnerWithRegistry(emptyList(), ToolRegistry(emptySet()))
    planRepository.saveProposal(
      plan(id = "plan-1", agentId = agent.id),
      planVersion(planId = "plan-1", steps = listOf(planStep(id = "step-1"))),
    )
    planRepository.approve("plan-1", 1)
    db.heartbeatDao().insert(planHeartbeat(agentId = agent.id))

    val outcome = runner.handleFire("hb1", attempt = 0)

    assertEquals(FireOutcome.DONE, outcome) // never RETRY — resolves immediately, never waits on a screen
    assertTrue("the step turn's LLM must never even be called once BEFORE_PLAN_STEP blocks it", llm.calls.isEmpty())
    assertEquals(StepStatus.BLOCKED, planRepository.snapshot("plan-1")!!.stepStates.single().status)
    assertTrue(
      "user must be told the plan step needs attention",
      notifier.calls.any { it.preview.contains("Шаг плана требует внимания") },
    )
    // Plan stays ACTIVE (a blocked STEP never flips whole-plan status) -> re-synced, not cancelled
    // outright; PlanScheduler itself (tested separately) finds nothing selectable and cancels.
    assertEquals(listOf("plan-1"), planScheduleSync.synced)
    assertTrue(planScheduleSync.cancelled.isEmpty())
  }

  @Test
  fun `a plain reminder heartbeat and a typed plan-step heartbeat dispatch independently through the same runner`() = runTest {
    db.heartbeatDao().insert(hb(mode = "push"))
    val (runner, _, planRepository, _) = runnerWithRegistry(listOf(StreamEvent.Finish("stop")), ToolRegistry(emptySet()))
    planRepository.saveProposal(plan(id = "plan-1"), planVersion(planId = "plan-1", steps = listOf(planStep(id = "step-1"))))
    planRepository.approve("plan-1", 1)
    db.heartbeatDao().insert(planHeartbeat(id = "hb-plan"))

    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0)) // the plain push
    assertEquals(FireOutcome.DONE, runner.handleFire("hb-plan", attempt = 0)) // the typed plan step

    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    assertEquals("fired", db.heartbeatDao().getById("hb-plan")?.status)
    assertEquals(2, notifier.calls.size) // both independently notified — plain push text, plan-completion text
    assertEquals(PlanStatus.COMPLETED, planRepository.snapshot("plan-1")!!.plan.status)
  }

  @Test
  fun `a stale plan-step heartbeat (approved version has moved on) is skipped, not executed`() = runTest {
    val (runner, llm, planRepository, planScheduleSync) = runnerWithRegistry(listOf(StreamEvent.Finish("stop")), ToolRegistry(emptySet()))
    planRepository.saveProposal(plan(id = "plan-1"), planVersion(planId = "plan-1", version = 1, steps = listOf(planStep(id = "step-1"))))
    planRepository.approve("plan-1", 1)
    // Material revision approved as v2 while the heartbeat below still references v1.
    planRepository.saveProposal(
      plan(id = "plan-1", status = PlanStatus.ACTIVE, approvedVersion = 1),
      planVersion(planId = "plan-1", version = 2, steps = listOf(planStep(id = "step-1"))),
    )
    planRepository.approve("plan-1", 2)
    db.heartbeatDao().insert(planHeartbeat(planVersion = 1)) // stale: references v1, plan is now on v2

    val outcome = runner.handleFire("hb1", attempt = 0)

    assertEquals(FireOutcome.DONE, outcome)
    assertTrue("a stale heartbeat must never reach the LLM", llm.calls.isEmpty())
    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
    assertTrue("a stale heartbeat's own skip must not itself touch the scheduler", planScheduleSync.synced.isEmpty())
    assertTrue(planScheduleSync.cancelled.isEmpty())
  }

  @Test
  fun `a plan-step heartbeat for a paused plan is skipped, not executed`() = runTest {
    val (runner, llm, planRepository, _) = runnerWithRegistry(listOf(StreamEvent.Finish("stop")), ToolRegistry(emptySet()))
    planRepository.saveProposal(plan(id = "plan-1"), planVersion(planId = "plan-1", steps = listOf(planStep(id = "step-1"))))
    planRepository.approve("plan-1", 1)
    planRepository.pause("plan-1")
    db.heartbeatDao().insert(planHeartbeat())

    val outcome = runner.handleFire("hb1", attempt = 0)

    assertEquals(FireOutcome.DONE, outcome)
    assertTrue("a paused plan's step must never reach the LLM", llm.calls.isEmpty())
    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
  }
}
