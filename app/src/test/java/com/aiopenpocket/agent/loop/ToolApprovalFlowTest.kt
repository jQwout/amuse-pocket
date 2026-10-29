package com.aiopenpocket.agent.loop

// @trace user-agent-builder-848153
// @trace approval-outcome-honesty-554a8c

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
import com.aiopenpocket.agent.core.mcp.MalformedArguments
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.policy.ApprovalOutcome
import com.aiopenpocket.agent.core.policy.ApprovalResultText
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ToolApprovalCoordinator] proven two ways (task-9-brief.md Step 4/5/9):
 *  - end to end through the REAL [ReactLoopRunner] gate — approving really lets
 *    [com.aiopenpocket.agent.core.mcp.ToolExecutor] run the tool, denying really blocks it, and a
 *    non-interactive (background) run never even registers with the coordinator;
 *  - directly against the coordinator's own contract — timeout, cancellation, and foreign-run
 *    rejection don't need the loop/hook/Room machinery to prove at all.
 *
 * Since 28 Oct 2026 it also holds the line that "the tool did not run" is TWO facts, not one: a
 * decline and an unanswered request reach the model as separately named texts, and only a person
 * pressing the button can ever produce the one that mentions refusal (approval-outcome-honesty-554a8c).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
class ToolApprovalFlowTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** One canned event list per `streamChat` call, same shape as ReactLoopHooksTest's. */
  private class ScriptedLlm(private val perCall: List<List<StreamEvent>>) : LlmClient {
    var calls = 0
      private set

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      val events = perCall.getOrElse(calls) { listOf(StreamEvent.Finish("stop")) }
      calls++
      return flowOf(*events.toTypedArray())
    }
  }

  /** Fake with a real (empty-args) schema, and a flag proving whether execute() actually ran. */
  private class FakeTool(override val name: String, override val riskClass: RiskClass = RiskClass.READ) : Tool {
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

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private fun runner(llm: LlmClient, toolRegistry: ToolRegistry, coordinator: ToolApprovalCoordinator) = ReactLoopRunner(
    llm,
    ToolExecutor(toolRegistry),
    toolRegistry,
    MemoryRepository(db.factDao(), db.episodeDao()),
    NoopLog,
    AgentHookRepository(db.agentHookDao()),
    coordinator,
  )

  /** Every BEFORE_TOOL call requires approval, unconditionally (conditionType="always"). */
  private fun agentWithApprovalHook(toolNames: Set<String>) = AgentDefinition(
    id = "approval-agent",
    source = AgentSource.USER,
    name = "Агент с approval-хуком",
    description = "Тестовый агент для проверки ToolApprovalCoordinator.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:approval-agent:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    hooks = listOf(
      AgentHook(
        id = "h-approval",
        agentId = "approval-agent",
        event = HookEvent.BEFORE_TOOL,
        conditionType = "always",
        conditionJson = "{}",
        actionType = HookActionType.REQUIRE_APPROVAL,
        actionJson = "{}",
        priority = 0,
        enabled = true,
      ),
    ),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  /** iter 1: a tool_call for [toolName]; iter 2: plain text "done" so the run finishes cleanly either way. */
  private fun toolCallThenStop(toolName: String) = listOf(
    listOf(StreamEvent.ToolCall("call-1", toolName, emptyMap()), StreamEvent.Finish("tool_calls")),
    listOf(StreamEvent.TextDelta("done"), StreamEvent.Finish("stop")),
  )

  // --- end to end through the real ReactLoopRunner gate -----------------------------------------

  @Test
  fun `approving the pending request executes the tool for real`() = runTest(UnconfinedTestDispatcher()) {
    val tool = FakeTool("fake_tool")
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("fake_tool")), toolRegistry, coordinator)

    // Dispatchers.Default, not runTest's virtual clock: while the test body waits for the request to be
    // published, runTest winds time forward and the 120-second approval timeout fires instantly — the turn
    // gave up waiting even before resolve(), and both tests below would check the timeout instead of the
    // user's decision (the deny test did pass that way — for the wrong reason). A real clock is needed here.
    val resultDeferred = async(Dispatchers.Default) {
      runner.run(
        conversationId = "c1",
        messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
        tools = toolRegistry.getAllSchemas(),
        provider = LlmProvider.OPENROUTER,
        agent = agentWithApprovalHook(setOf("fake_tool")),
      ) { }
    }

    // .first{} (not .value) - correct regardless of exactly which thread/dispatch order the Room
    // journal write inside fireHook lands on; it genuinely waits for the real publish rather than
    // assuming a specific interleaving.
    val pending = coordinator.pending.first { it.isNotEmpty() }.single()
    assertEquals("fake_tool", pending.toolName)
    assertEquals(RiskClass.READ, pending.riskClass) // FakeTool's default risk: here only the HOOK asks, not the floor

    assertTrue(coordinator.resolve(pending.runId, pending.requestId, approved = true))
    resultDeferred.await()

    assertTrue("approval must let the real tool.execute() run", tool.executed)
    assertTrue("resolved request must be removed from pending", coordinator.pending.value.isEmpty())
  }

  @Test
  fun `denying the pending request blocks the tool - it never executes`() = runTest(UnconfinedTestDispatcher()) {
    val tool = FakeTool("fake_tool")
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("fake_tool")), toolRegistry, coordinator)

    // Dispatchers.Default, not runTest's virtual clock: while the test body waits for the request to be
    // published, runTest winds time forward and the 120-second approval timeout fires instantly — the turn
    // gave up waiting even before resolve(), and both tests below would check the timeout instead of the
    // user's decision (the deny test did pass that way — for the wrong reason). A real clock is needed here.
    val resultDeferred = async(Dispatchers.Default) {
      runner.run(
        conversationId = "c1",
        messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
        tools = toolRegistry.getAllSchemas(),
        provider = LlmProvider.OPENROUTER,
        agent = agentWithApprovalHook(setOf("fake_tool")),
      ) { }
    }

    val pending = coordinator.pending.first { it.isNotEmpty() }.single()
    assertTrue(coordinator.resolve(pending.runId, pending.requestId, approved = false))

    val result = resultDeferred.await()
    assertFalse("denial must never let the tool execute", tool.executed)
    val toolResult = result.toolTrace.single { it.toolName == "fake_tool" }.result
    assertTrue(toolResult is ToolResult.Error)
    // Named, not just "an error": a decline is the one outcome the model may legitimately describe as
    // the user's choice, so it must arrive as THAT text and no other.
    assertEquals(ApprovalResultText.declined("fake_tool"), (toolResult as ToolResult.Error).message)
  }

  // --- the two ways of not running: the user's "no" vs nobody's answer ---------------------------

  /**
   * The defect this pair exists for (Android 14, 28 Oct 2026): ask for a mail draft, minimize the app,
   * and the request expired on its own. The model was handed one text covering both cases — «declined
   * it or did not answer in time» — read the "or", and told the person «Not sent — declined in chat.»
   * They had declined nothing; the card had never been in front of them. So the two outcomes must
   * reach the model as two different, separately named texts, and the no-answer one must carry no
   * notion of refusal at all — in any language, because the model answers in the user's.
   */
  @Test
  fun `nobody answering hands the model the waiting text, not the decline text`() = runTest {
    val tool = FakeTool("fake_tool", RiskClass.EXTERNAL)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("fake_tool")), toolRegistry, coordinator)

    // No resolve() anywhere, and deliberately ON runTest's virtual clock (the tests above take pains to
    // avoid it): time winds through the whole approval window in an instant, which is precisely the
    // "the phone was face down on the table" case.
    val result = runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
    ) { }

    assertFalse("nothing may run when nobody answered", tool.executed)
    val message = (result.toolTrace.single { it.toolName == "fake_tool" }.result as ToolResult.Error).message
    assertEquals(ApprovalResultText.unanswered("fake_tool"), message)
    assertNotEquals(
      "an expired request must not be reported with the text written for a real decline",
      ApprovalResultText.declined("fake_tool"),
      message,
    )
    REFUSAL_WORDS.forEach { word ->
      assertFalse(
        "the no-answer text must not contain '$word' — the model repeats the salient word: $message",
        message.lowercase().contains(word),
      )
    }
    // The other half: the decline text is allowed to say it, otherwise "contains no refusal word"
    // would be satisfied by making both texts vague again.
    assertTrue(
      "the decline text must name the refusal: ${ApprovalResultText.declined("fake_tool")}",
      REFUSAL_WORDS.any { ApprovalResultText.declined("fake_tool").lowercase().contains(it) },
    )
  }

  @Test
  fun `a non-interactive (background) run never registers with the coordinator - immediate deny`() = runTest {
    val tool = FakeTool("fake_tool")
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("fake_tool")), toolRegistry, coordinator)

    runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
      agent = agentWithApprovalHook(setOf("fake_tool")),
      interactiveApproval = false,
    ) { }

    assertFalse("no screen could ever answer, so the tool must never run", tool.executed)
    assertTrue("a non-interactive run must never publish a request nobody will see", coordinator.pending.value.isEmpty())
  }

  // --- the LIVE floor: DESTRUCTIVE/EXTERNAL ask even with no agent and no hooks -----------------

  @Test
  fun `plain chat with no hooks still asks before a DESTRUCTIVE call, and approving runs it`() = runTest(UnconfinedTestDispatcher()) {
    val tool = FakeTool("calendar", RiskClass.DESTRUCTIVE)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("calendar")), toolRegistry, coordinator)

    // A real clock for the same reason as in the tests above: runTest's virtual time would instantly
    // "wait out" the two-minute approval timeout.
    val resultDeferred = async(Dispatchers.Default) {
      runner.run(
        conversationId = "c1",
        messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
        tools = toolRegistry.getAllSchemas(),
        provider = LlmProvider.OPENROUTER,
        agent = null, // a plain chat: no agent, no hooks — the floor must work on its own
      ) { }
    }

    val pending = coordinator.pending.first { it.isNotEmpty() }.single()
    assertEquals(RiskClass.DESTRUCTIVE, pending.riskClass)
    assertFalse("nothing may run before the human answers", tool.executed)

    assertTrue(coordinator.resolve(pending.runId, pending.requestId, approved = true))
    resultDeferred.await()
    assertTrue(tool.executed)
  }

  @Test
  fun `a DESTRUCTIVE call with unparseable arguments never asks the person - the model hears the JSON error`() = runTest {
    val tool = FakeTool("calendar", RiskClass.DESTRUCTIVE)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "calendar", MalformedArguments.marker("{\"title\": ")), StreamEvent.Finish("tool_calls")),
        listOf(StreamEvent.TextDelta("done"), StreamEvent.Finish("stop")),
      ),
    )
    var askedForApproval = false

    val result = runner(llm, toolRegistry, coordinator).run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
      agent = null,
    ) { event -> if (event is AgentEvent.ToolApprovalRequired) askedForApproval = true }

    assertFalse("a call that can never run must not be put to the person", askedForApproval)
    assertFalse(tool.executed)
    val traced = result.toolTrace.single().result
    assertTrue(traced is ToolResult.Error && traced.message.contains("not valid JSON"))
  }

  @Test
  fun `background run with no hooks denies an EXTERNAL call outright`() = runTest {
    val tool = FakeTool("email", RiskClass.EXTERNAL)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("email")), toolRegistry, coordinator)

    val result = runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
      interactiveApproval = false,
    ) { }

    assertFalse(tool.executed)
    assertTrue(coordinator.pending.value.isEmpty())
    assertTrue(result.toolTrace.single { it.toolName == "email" }.result is ToolResult.Error)
  }

  @Test
  fun `a WRITE call below the floor runs without asking`() = runTest {
    val tool = FakeTool("notes", RiskClass.WRITE)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("notes")), toolRegistry, coordinator)

    runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
    ) { }

    assertTrue(tool.executed)
  }

  // --- coordinator contract directly (no Room/hooks/loop involved) ------------------------------

  private fun request(requestId: String = "req-1", runId: String = "run-1", conversationId: String = "conv-1") =
    ToolApprovalRequest(
      requestId = requestId,
      runId = runId,
      conversationId = conversationId,
      toolName = "calendar",
      riskClass = RiskClass.DESTRUCTIVE,
      arguments = emptyMap(),
    )

  /**
   * The request must already be pending when the world first hears of it. TurnManager starts its
   * "push if the app is minimised" watcher on this very event, and the watcher's first move is to ask
   * whether the request is still pending. When the event went out before the coordinator registered
   * the request, a watcher that happened to run first saw "not pending", read it as "already answered"
   * and never pushed — on Android 14 (29 Oct 2026) the push came on one run and not on the next. An
   * "Allow" tapped in that gap would have been lost the same way: resolve() finds nothing to resolve.
   * The push tests could not see this, because their pending-approvals port is a fake that always
   * says yes; this one uses the real coordinator.
   */
  @Test
  fun `a request is already pending when its approval event goes out`() = runTest {
    val tool = FakeTool("fake_tool", RiskClass.EXTERNAL)
    val toolRegistry = ToolRegistry(setOf(tool))
    val coordinator = ToolApprovalCoordinator()
    val runner = runner(ScriptedLlm(toolCallThenStop("fake_tool")), toolRegistry, coordinator)

    val pendingWhenAnnounced = mutableListOf<Boolean>()
    runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "system", content = "sys")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
    ) { event ->
      if (event is AgentEvent.ToolApprovalRequired) {
        pendingWhenAnnounced += coordinator.isPending(event.request.requestId)
      }
    }

    assertEquals(listOf(true), pendingWhenAnnounced)
  }

  @Test
  fun `unanswered request times out to UNANSWERED, never to DECLINED`() = runTest {
    val coordinator = ToolApprovalCoordinator()

    val outcome = coordinator.awaitApproval(request(), timeoutMs = 1_000L)

    assertEquals(ApprovalOutcome.UNANSWERED, outcome)
    assertTrue("timed-out request must be cleaned up, not left dangling in pending", coordinator.pending.value.isEmpty())
  }

  /** The button, and only the button, makes a DECLINED — that is the whole invariant the result text rests on. */
  @Test
  fun `only the Decline button produces DECLINED`() = runTest(UnconfinedTestDispatcher()) {
    val coordinator = ToolApprovalCoordinator()
    val req = request(requestId = "req-no", runId = "run-no")

    val outcome = async { coordinator.awaitApproval(req, timeoutMs = 60_000L) }
    coordinator.resolve("run-no", "req-no", approved = false)

    assertEquals(ApprovalOutcome.DECLINED, outcome.await())
  }

  @Test
  fun `isPending follows one request from published to resolved`() = runTest(UnconfinedTestDispatcher()) {
    val coordinator = ToolApprovalCoordinator()
    val req = request(requestId = "req-watch", runId = "run-watch")

    assertFalse(coordinator.isPending("req-watch"))
    val outcome = async { coordinator.awaitApproval(req, timeoutMs = 60_000L) }
    assertTrue("the push gate must see a published request", coordinator.isPending("req-watch"))

    coordinator.resolve("run-watch", "req-watch", approved = true)
    outcome.await()

    assertFalse("an answered request must stop being pending, or the push would nudge about nothing", coordinator.isPending("req-watch"))
  }

  @Test
  fun `cancelRun stops every pending wait of that run as UNANSWERED`() = runTest(UnconfinedTestDispatcher()) {
    val coordinator = ToolApprovalCoordinator()
    val req = request(requestId = "req-cancel", runId = "run-cancel")

    val outcomeDeferred = async { coordinator.awaitApproval(req, timeoutMs = 60_000L) }
    assertEquals(1, coordinator.pending.value.size)

    coordinator.cancelRun("run-cancel")

    // UNANSWERED, not DECLINED: a run torn down from outside says nothing about what the user wanted.
    assertEquals(ApprovalOutcome.UNANSWERED, outcomeDeferred.await())
    assertTrue(coordinator.pending.value.isEmpty())
  }

  @Test
  fun `abandonAllPending stops every currently open wait regardless of run`() = runTest(UnconfinedTestDispatcher()) {
    val coordinator = ToolApprovalCoordinator()
    val reqA = request(requestId = "req-a", runId = "run-a")
    val reqB = request(requestId = "req-b", runId = "run-b")

    val a = async { coordinator.awaitApproval(reqA, timeoutMs = 60_000L) }
    val b = async { coordinator.awaitApproval(reqB, timeoutMs = 60_000L) }
    assertEquals(2, coordinator.pending.value.size)

    coordinator.abandonAllPending()

    assertEquals(ApprovalOutcome.UNANSWERED, a.await())
    assertEquals(ApprovalOutcome.UNANSWERED, b.await())
  }

  @Test
  fun `a foreign runId cannot resolve someone else's pending request`() = runTest(UnconfinedTestDispatcher()) {
    val coordinator = ToolApprovalCoordinator()
    val req = request(requestId = "req-x", runId = "run-owner")

    val outcomeDeferred = async { coordinator.awaitApproval(req, timeoutMs = 60_000L) }

    val resolvedByForeignRun = coordinator.resolve(runId = "run-intruder", requestId = "req-x", approved = true)
    assertFalse("a mismatched runId must not resolve another run's request", resolvedByForeignRun)
    assertEquals("the foreign attempt must have had no effect - still pending", 1, coordinator.pending.value.size)

    val resolvedByOwner = coordinator.resolve(runId = "run-owner", requestId = "req-x", approved = true)
    assertTrue(resolvedByOwner)
    assertEquals(ApprovalOutcome.APPROVED, outcomeDeferred.await())
  }

  @Test
  fun `resolving an unknown requestId is a harmless no-op`() = runTest {
    val coordinator = ToolApprovalCoordinator()

    assertFalse(coordinator.resolve(runId = "any", requestId = "never-existed", approved = true))
  }

  private companion object {
    /**
     * Words a person would read as "you said no", in both interface languages (the model answers in
     * the user's, so a Russian stem in an English prompt still comes out as an accusation in Russian).
     * Stems, not whole words: "declined"/"declining"/"decline" all start the same.
     */
    val REFUSAL_WORDS = listOf(
      "declin", "refus", "reject", "deny", "denied", "turned it down", "said no",
      "отказ", "отклон", "запрет", "не разрешил",
    )
  }
}
