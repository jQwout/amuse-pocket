package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153

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
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.loop.AgentMode
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

  private class FakeLlm(private val events: List<StreamEvent>) : LlmClient {
    /** Snapshot (not a live reference) of `messages` as seen on each `streamChat` call — lets a test
     * assert on the actual system-prompt content sent, without risking a later in-place mutation of
     * the caller's mutable list retroactively changing what a prior call "saw". */
    val calls = mutableListOf<List<ChatMessage>>()
    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      calls += messages.toList()
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

  /** Bundles a [HeartbeatRunner] with the [FakeLlm] double it was wired to, for tests that need to
   * inspect what actually reached the "model" (e.g. the system prompt) — [runner] itself only
   * returns the former, which is all the pre-Task-22 tests ever needed. */
  private data class Doubles(val runner: HeartbeatRunner, val llm: FakeLlm)

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
    )
    val conversationRepository = ConversationRepository(
      db.conversationDao(),
      db.conversationTagDao(),
      db.messageDao(),
    )
    val agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao())))
    val runner = HeartbeatRunner(
      db.heartbeatDao(),
      reactLoopRunner,
      toolRegistry,
      conversationRepository,
      notifier,
      agentResolver,
      turnContextHolder,
    )
    return Doubles(runner, fakeLlm)
  }

  private fun runner(events: List<StreamEvent>): HeartbeatRunner =
    runnerWithRegistry(events, ToolRegistry(emptySet())).runner

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
}
