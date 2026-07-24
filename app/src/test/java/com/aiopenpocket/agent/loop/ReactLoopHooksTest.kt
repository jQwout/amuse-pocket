package com.aiopenpocket.agent.loop

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
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
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
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
 * [ReactLoopRunner] wired to a REAL [HookEngine][com.aiopenpocket.agent.core.hook.HookEngine] and a
 * real (in-memory Room) [AgentHookRepository] journal — not a fake — so these prove the lifecycle
 * weaving (Task 8 Step 4) end to end: all six events actually reach the engine at the right point in
 * the loop, `ADD_INSTRUCTION` really mutates `messages` at the head and really is gone the next
 * iteration, `DENY` really stops [ToolResult] from ever being produced by the tool itself, and `STOP`
 * really halts before the next side effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactLoopHooksTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** One canned event list per `streamChat` call; also records the exact `messages` snapshot it was given, so ephemeral-instruction presence/absence is directly observable. */
  private class ScriptedLlm(private val perCall: List<List<StreamEvent>>) : LlmClient {
    val callsSeenMessages = mutableListOf<List<ChatMessage>>()
    var calls = 0
      private set

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      callsSeenMessages += messages.toList()
      val events = perCall.getOrElse(calls) { listOf(StreamEvent.Finish("stop")) }
      calls++
      return flowOf(*events.toTypedArray())
    }
  }

  /** Fake with a real (empty-args) schema, and a flag proving whether the runtime ever actually called `execute`. */
  private class FakeTool(override val name: String) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    var executed = false
      private set

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
      executed = true
      return ToolResult.Success("ok:$name")
    }
  }

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var hookRepository: AgentHookRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    hookRepository = AgentHookRepository(db.agentHookDao())
  }

  @After
  fun tearDown() = db.close()

  private fun runner(llm: LlmClient, toolRegistry: ToolRegistry = ToolRegistry(emptySet())) = ReactLoopRunner(
    llm,
    ToolExecutor(toolRegistry),
    toolRegistry,
    MemoryRepository(db.factDao(), db.episodeDao()),
    NoopLog,
    hookRepository,
    ToolApprovalCoordinator(),
  )

  private fun agentWithHooks(hooks: List<AgentHook>, toolNames: Set<String> = emptySet()) = AgentDefinition(
    id = "hook-agent",
    source = AgentSource.USER,
    name = "Агент с хуками",
    description = "Тестовый агент для проверки HookEngine-интеграции.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:hook-agent:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = hooks,
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun hook(
    id: String,
    event: HookEvent,
    actionType: HookActionType,
    actionJson: String = "{}",
    conditionType: String = "always",
    conditionJson: String = "{}",
    priority: Int = 0,
  ) = AgentHook(
    id = id,
    agentId = "hook-agent",
    event = event,
    conditionType = conditionType,
    conditionJson = conditionJson,
    actionType = actionType,
    actionJson = actionJson,
    priority = priority,
    enabled = true,
  )

  private fun textThenStop(text: String) = listOf(StreamEvent.TextDelta(text), StreamEvent.Finish("stop"))

  // --- all six events actually reach the engine -----------------------------------------------

  @Test
  fun `all six lifecycle events fire and are journaled across a turn with a tool call`() = runTest {
    val logHooks = listOf(
      hook("h-before-turn", HookEvent.BEFORE_TURN, HookActionType.LOG, """{"text":"bt"}"""),
      hook("h-after-turn", HookEvent.AFTER_TURN, HookActionType.LOG, """{"text":"at"}"""),
      hook("h-before-tool", HookEvent.BEFORE_TOOL, HookActionType.LOG, """{"text":"bto"}"""),
      hook("h-after-tool", HookEvent.AFTER_TOOL, HookActionType.LOG, """{"text":"ato"}"""),
      hook("h-complete", HookEvent.ON_COMPLETE, HookActionType.LOG, """{"text":"comp"}"""),
    )
    val toolRegistry = ToolRegistry(setOf(FakeTool("fake_tool")))
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "fake_tool", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("done"),
      ),
    )

    runner(llm, toolRegistry).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(logHooks, toolNames = setOf("fake_tool")),
    ) { }

    // Non-vacuous: every one of the 5 hooks (one per non-error event) has at least one journaled
    // `agent_hook_runs` row - proves each event point in the loop actually invoked HookEngine, not
    // just that the run completed successfully regardless of hooks.
    for (id in listOf("h-before-turn", "h-after-turn", "h-before-tool", "h-after-tool", "h-complete")) {
      assertTrue("hook '$id' should have fired at least once", hookRepository.getRunsForHook(id).isNotEmpty())
    }
  }

  @Test
  fun `ON_ERROR fires and is journaled on a stream error`() = runTest {
    val errorHook = hook("h-error", HookEvent.ON_ERROR, HookActionType.LOG, """{"text":"err"}""")
    val llm = ScriptedLlm(listOf(listOf(StreamEvent.Error("boom"))))

    val result = runner(llm).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = emptyList(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(errorHook)),
    ) { }

    assertEquals(AgentState.ERROR, result.state)
    assertTrue(hookRepository.getRunsForHook("h-error").isNotEmpty())
  }

  @Test
  fun `plain chat (no bound agent) never touches the hook engine or journal`() = runTest {
    // Sanity check for "hooks fire only for a bound agent" - agent = null must behave exactly as
    // before Task 8, with zero hook-related side effects even though nothing prevents it structurally.
    val llm = ScriptedLlm(listOf(textThenStop("hi")))

    runner(llm).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = emptyList(),
      provider = LlmProvider.NIM,
      agent = null,
    ) { }

    assertTrue(hookRepository.getRunsForHook("h-before-turn").isEmpty())
  }

  // --- ADD_INSTRUCTION: ephemeral, head-only, gone next iteration ------------------------------

  @Test
  fun `ADD_INSTRUCTION inserts an ephemeral head system message that does not accumulate turn over turn`() = runTest {
    val instructionHook = hook("h-instr", HookEvent.BEFORE_TURN, HookActionType.ADD_INSTRUCTION, """{"text":"EPHEMERAL_MARKER"}""")
    val toolRegistry = ToolRegistry(setOf(FakeTool("fake_tool")))
    // Two turns: iter 1 calls a tool (so there IS a "next iteration" to inspect), iter 2 finishes.
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "fake_tool", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("done"),
      ),
    )

    runner(llm, toolRegistry).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(instructionHook), toolNames = setOf("fake_tool")),
    ) { }

    assertEquals(2, llm.calls)
    val firstCallMessages = llm.callsSeenMessages[0]
    val secondCallMessages = llm.callsSeenMessages[1]

    // Iter 1: [system, ephemeral] - inserted right after the base system message (index 1), head not tail.
    assertEquals(2, firstCallMessages.size)
    assertEquals("system", firstCallMessages[1].role)
    assertTrue(firstCallMessages[1].content.orEmpty().contains("EPHEMERAL_MARKER"))

    // Iter 2: [system, ephemeral(fresh), assistant(tool_calls), tool] - size 4, NOT 5. If iter 1's
    // ephemeral had leaked (not removed), it would still be sitting at index 1 and this iteration's
    // fresh insert would push the count to 5 instead of 4 - the exact accumulation bug the brief warns
    // about ("messages мутируется in-place... разрыв пары даёт HTTP 400").
    assertEquals(4, secondCallMessages.size)
    assertEquals("system", secondCallMessages[1].role)
    assertTrue(secondCallMessages[1].content.orEmpty().contains("EPHEMERAL_MARKER"))
    assertEquals("assistant", secondCallMessages[2].role)
    assertEquals("tool", secondCallMessages[3].role)
  }

  // --- DENY blocks a tool -----------------------------------------------------------------------

  @Test
  fun `DENY blocks a tool - it is never actually executed`() = runTest {
    val denyHook = hook(
      id = "h-deny",
      event = HookEvent.BEFORE_TOOL,
      actionType = HookActionType.DENY,
      conditionType = "tool_name_in",
      conditionJson = """{"names":["fake_tool"]}""",
    )
    val tool = FakeTool("fake_tool")
    val toolRegistry = ToolRegistry(setOf(tool))
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "fake_tool", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("done"),
      ),
    )

    val result = runner(llm, toolRegistry).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(denyHook), toolNames = setOf("fake_tool")),
    ) { }

    assertFalse("the tool's own execute() must never run once denied", tool.executed)
    val toolResult = result.toolTrace.single { it.toolName == "fake_tool" }.result
    assertTrue(toolResult is ToolResult.Error)
    assertTrue((toolResult as ToolResult.Error).message.contains("denied"))
  }

  @Test
  fun `REQUIRE_APPROVAL with no approval mechanism available is treated as a deny, not silently executed`() = runTest {
    val approvalHook = hook(id = "h-approval", event = HookEvent.BEFORE_TOOL, actionType = HookActionType.REQUIRE_APPROVAL)
    val tool = FakeTool("fake_tool")
    val toolRegistry = ToolRegistry(setOf(tool))
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "fake_tool", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("done"),
      ),
    )

    runner(llm, toolRegistry).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(approvalHook), toolNames = setOf("fake_tool")),
    ) { }

    assertFalse("Task 9 builds the real approval UI - until then this must never silently execute", tool.executed)
  }

  // --- STOP halts before the next side effect/LLM call ------------------------------------------

  @Test
  fun `BEFORE_TURN STOP halts before the LLM is even called`() = runTest {
    val stopHook = hook(id = "h-stop", event = HookEvent.BEFORE_TURN, actionType = HookActionType.STOP)
    val llm = ScriptedLlm(listOf(textThenStop("should never be reached")))

    runner(llm).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = emptyList(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(stopHook)),
    ) { }

    assertEquals("BEFORE_TURN STOP must prevent streamChat from being called at all", 0, llm.calls)
  }

  @Test
  fun `BEFORE_TOOL STOP prevents this and every later tool call this turn, still pairs every tool_call, halts before iter 2`() = runTest {
    val stopHook = hook(
      id = "h-stop",
      event = HookEvent.BEFORE_TOOL,
      actionType = HookActionType.STOP,
      conditionType = "tool_name_in",
      conditionJson = """{"names":["first"]}""",
    )
    val firstTool = FakeTool("first")
    val secondTool = FakeTool("second")
    val toolRegistry = ToolRegistry(setOf(firstTool, secondTool))
    val llm = ScriptedLlm(
      listOf(
        listOf(
          StreamEvent.ToolCall("call-1", "first", emptyMap()),
          StreamEvent.ToolCall("call-2", "second", emptyMap()),
          StreamEvent.Finish("tool_calls"),
        ),
        textThenStop("should never be reached - run already stopped"),
      ),
    )
    val messages = mutableListOf(ChatMessage(role = "system", content = "sys"))

    runner(llm, toolRegistry).run(
      conversationId = "c1",
      messages = messages,
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.NIM,
      agent = agentWithHooks(listOf(stopHook), toolNames = setOf("first", "second")),
    ) { }

    assertFalse("the STOPped tool itself must not execute", firstTool.executed)
    assertFalse("a later tool_call in the SAME assistant message must not execute either", secondTool.executed)
    assertEquals("the run must halt before a second streamChat call", 1, llm.calls)

    // Protocol pairing: both tool_calls from the one assistant message still got an answering
    // tool-result, even though neither actually executed - otherwise a real provider would 400.
    val toolResultIds = messages.filter { it.role == "tool" }.map { it.toolCallId }.toSet()
    assertEquals(setOf("call-1", "call-2"), toolResultIds)
  }
}
