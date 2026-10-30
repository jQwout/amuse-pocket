package com.aiopenpocket.agent.loop

// @trace rezhim-refleksii-fca3c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.context.PlanContextProvider
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.agent.tools.MemoryTools
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aiopenpocket.testing.testDataStore

/**
 * Checks the REFLECT empty-final gate (a code review finding): a revise with no text and no card must give
 * a terminal error + "Retry", not be persisted as three decorative headers ✍/🔍/✨, which make the
 * transcript non-empty and would get past the blank gate of resolveCompletedTurn.
 *
 * A real [ReactLoopRunner] + [PrePromptAssembler] on in-memory Room; the LLM is a scripted fake that
 * returns one stream per call (draft → critique → revise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReflectionOrchestratorTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** Returns one event script per streamChat call: [0]=draft, [1]=critique, [2]=revise. */
  private class ScriptedLlm(private val perCall: List<List<StreamEvent>>) : LlmClient {
    var calls = 0
      private set

    /** A snapshot of the messages and tool names on every call — to check what the revise phase saw. */
    val seenMessages = mutableListOf<List<ChatMessage>>()
    val seenToolNames = mutableListOf<List<String>>()

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      seenMessages += messages.toList()
      seenToolNames += tools.map { it.function.name }
      val events = perCall.getOrElse(calls) { listOf(StreamEvent.Finish("stop")) }
      calls++
      return flowOf(*events.toTypedArray())
    }
  }

  /** A writing tool with a counter: proves how many times the action REALLY ran. */
  private class CountingWriteTool(override val name: String) : Tool {
    override val description = "write tool $name"
    override val schema = toolSchema(name, description) {}
    override val riskClass = RiskClass.WRITE
    var executions = 0
      private set
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
      executions++
      return ToolResult.Success("saved")
    }
  }

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  /** Fresh wrapper per call, always backed by the same in-memory [db] - AgentHookRepository is stateless. */
  private fun agentHookRepository() = AgentHookRepository(db.agentHookDao())

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  /** Fake with a real (empty-args) schema, so [ToolRegistry.getSchemasFor] can scope by name. */
  private class FakeTool(override val name: String) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  private fun orchestrator(
    llm: ScriptedLlm,
    toolRegistry: ToolRegistry = ToolRegistry(emptySet()),
    turnContextHolder: TurnContextHolder = TurnContextHolder(),
  ): ReflectionOrchestrator {
    val memoryRepository = MemoryRepository(db.factDao(), db.episodeDao())
    val conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    val assembler = PrePromptAssembler(
      assets = AssetSource { null },
      memoryRepository = memoryRepository,
      conversationRepository = conversationRepository,
      skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao())),
      userPreferencesRepository = UserPreferencesRepository(testDataStore(context)),
      agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), agentHookRepository())),
      planContextProvider = PlanContextProvider(PlanRepository(db, db.planDao())),
    )
    val reactLoopRunner = ReactLoopRunner(
      llm,
      ToolExecutor(toolRegistry),
      toolRegistry,
      memoryRepository,
      NoopLog,
      agentHookRepository(),
      ToolApprovalCoordinator(),
    )
    return ReflectionOrchestrator(
      llm,
      toolRegistry,
      assembler,
      reactLoopRunner,
      ConversationAnnotator(llm, conversationRepository) { LlmProvider.META },
      turnContextHolder,
      NoopLog,
    )
  }

  private fun textThenStop(text: String) = listOf(StreamEvent.TextDelta(text), StreamEvent.Finish("stop"))
  private val justStop = listOf(StreamEvent.Finish("stop"))

  @Test
  fun `пустой финал revise даёт Error и НЕ Completed`() = runTest {
    // draft and critique are non-empty, but revise returns nothing — a realistic "the revision gave nothing".
    val llm = ScriptedLlm(listOf(textThenStop("черновой ответ"), textThenStop("критика"), justStop))

    val events = orchestrator(llm).executeNim("c1", "вопрос").toList()

    val error = events.filterIsInstance<AgentEvent.Error>().singleOrNull()
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, error?.message)
    assertNull("пустой финал не должен персиститься как Completed", events.filterIsInstance<AgentEvent.Completed>().firstOrNull())
  }

  @Test
  fun `непустой финал revise даёт Completed с транскриптом`() = runTest {
    val llm = ScriptedLlm(listOf(textThenStop("черновой ответ"), textThenStop("критика"), textThenStop("Итоговый ответ")))

    val events = orchestrator(llm).executeNim("c1", "вопрос").toList()

    val completed = events.filterIsInstance<AgentEvent.Completed>().singleOrNull()
    assertTrue("финальный ответ должен попасть в транскрипт", completed?.result?.text?.contains("Итоговый ответ") == true)
    assertNull("успешный ход не должен эмитить Error", events.filterIsInstance<AgentEvent.Error>().firstOrNull())
  }

  @Test
  fun `REFLECT не обходит scoped allowlist кастомного агента - чужой тул отклоняется в draft- и revise-фазах`() = runTest {
    // The registry knows THREE tools (memory_tool + sms + email), but the agent allows only memory_tool —
    // before the fix ReflectionOrchestrator gave draft/revise the FULL registry.getAllSchemas() regardless
    // of the agent, so sms/email would pass as "available". That is exactly what scoped tools + the agent
    // gate must cut — both tools are outside the agent's toolNames, and each must be rejected
    // INDEPENDENTLY in its own phase (sms in draft, email in revise), not only in the first one met.
    val toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool"), FakeTool("sms"), FakeTool("email")))
    val userAgentRepository = UserAgentRepository(db, db.userAgentDao(), agentHookRepository())
    val agent = AgentDefinition(
      id = "my-agent",
      source = AgentSource.USER,
      name = "Мой агент",
      description = "Кастомный агент с одним разрешённым тулом.",
      iconKey = "sparkles",
      systemPrompt = "Ты — тестовый агент.",
      toolNames = setOf("memory_tool"),
      skillIds = emptySet(),
      defaultMode = AgentMode.REFLECT,
      allowedModes = setOf(AgentMode.CHAT, AgentMode.REFLECT),
      memoryPolicy = AgentMemoryPolicy(namespace = "agent:my-agent:", includeSharedProfile = false),
      allowedWidgetTemplates = emptySet(),
      configVersion = 0L,
      status = AgentStatus.DRAFT,
    )
    userAgentRepository.saveDraft(agent)
    userAgentRepository.publish(agent.id)
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = agent.id))

    // The draft "hallucinates" an sms call and the final revise an email one: BOTH tools are outside the
    // agent's allowlist (memory_tool only). The scripted LLM in the test ignores the schemas passed to it
    // (just as any model may inherit a tool name from the history or simply make a mistake) — which is
    // exactly why the check must be in the loop's runtime of EACH phase separately, not only in what the
    // model was shown in draft.
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "sms", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("черновой ответ"),
        textThenStop("критика"),
        listOf(StreamEvent.ToolCall("call-2", "email", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("Итоговый ответ"),
      ),
    )

    val events = orchestrator(llm, toolRegistry).executeNim("c1", "вопрос").toList()

    val smsCall = events.filterIsInstance<AgentEvent.ToolCompleted>().single { it.name == "sms" }
    val smsError = smsCall.result as? ToolResult.Error
    assertEquals("Tool 'sms' is not available in this context.", smsError?.message)

    // The same gate, but in the SECOND phase (revise) — before the fix ReflectionOrchestrator scoped only
    // draft, and revise would get the full registry, so this call would have gone unnoticed as a regression.
    val emailCall = events.filterIsInstance<AgentEvent.ToolCompleted>().single { it.name == "email" }
    val emailError = emailCall.result as? ToolResult.Error
    assertEquals("Tool 'email' is not available in this context.", emailError?.message)

    // A hallucinated tool is not a fatal loop error; the turn still reaches Completed.
    assertTrue(events.filterIsInstance<AgentEvent.Completed>().isNotEmpty())
  }

  @Test
  fun `REFLECT ход устанавливает контекст агента для memory_tool - remember_fact уходит в его namespace`() = runTest {
    // Task 5: before the fix ReflectionOrchestrator did not wrap draft/revise in
    // turnContextHolder.contextElement(...), so memory_tool inside a REFLECT turn would see
    // holder.agent == null (the global namespace) even for an agent with its own namespace — the same
    // class of hole as the REFLECT tool scope before the fix in Task 4, but for memory, not the tool allowlist.
    val turnContextHolder = TurnContextHolder()
    val memoryRepository = MemoryRepository(db.factDao(), db.episodeDao())
    val memoryTools = MemoryTools(memoryRepository, turnContextHolder)
    val toolRegistry = ToolRegistry(setOf(memoryTools))
    val userAgentRepository = UserAgentRepository(db, db.userAgentDao(), agentHookRepository())
    val agent = AgentDefinition(
      id = "my-agent",
      source = AgentSource.USER,
      name = "Мой агент",
      description = "Кастомный агент с доступом к памяти.",
      iconKey = "sparkles",
      systemPrompt = "Ты — тестовый агент.",
      toolNames = setOf("memory_tool"),
      skillIds = emptySet(),
      defaultMode = AgentMode.REFLECT,
      allowedModes = setOf(AgentMode.CHAT, AgentMode.REFLECT),
      memoryPolicy = AgentMemoryPolicy(namespace = "agent:my-agent:", includeSharedProfile = false),
      allowedWidgetTemplates = emptySet(),
      configVersion = 0L,
      status = AgentStatus.DRAFT,
    )
    userAgentRepository.saveDraft(agent)
    userAgentRepository.publish(agent.id)
    db.conversationDao().insert(ConversationEntity(id = "c1", title = "T", agentId = agent.id))

    val llm = ScriptedLlm(
      listOf(
        // DRAFT iteration 1: the model calls remember_fact right during the draft.
        listOf(
          StreamEvent.ToolCall(
            "call-1",
            "memory_tool",
            mapOf(
              "action" to JsonPrimitive("remember_fact"),
              "key" to JsonPrimitive("note"),
              "value" to JsonPrimitive("secret"),
            ),
          ),
          StreamEvent.Finish("tool_calls"),
        ),
        // DRAFT iteration 2: the final draft text (without it the loop would keep spinning).
        textThenStop("черновой ответ"),
        textThenStop("критика"),
        textThenStop("Итоговый ответ"),
      ),
    )

    orchestrator(llm, toolRegistry, turnContextHolder).executeNim("c1", "запомни note=secret").toList()

    val saved = db.factDao().getByKey("agent:my-agent:note")
    assertEquals("secret", saved?.value)
    assertEquals("general", saved?.category)
    // Without the namespace the key is not created at all — no "leak" into the global space remains.
    assertNull(db.factDao().getByKey("note"))
  }

  @Test
  fun `правка не повторяет действия черновика - получает только тулы чтения и список уже сделанного`() = runTest {
    // The draft in LIVE really runs the writing tool; the revise used to get the same tool set and the
    // instruction "call the needed tool again" — and the action ran a second time.
    val writeTool = CountingWriteTool("notes")
    val toolRegistry = ToolRegistry(setOf(writeTool, FakeTool("lookup")))
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "notes", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("черновой ответ"),
        textThenStop("критика"),
        // The revise calls the same writing tool once more "out of old habit".
        listOf(StreamEvent.ToolCall("call-2", "notes", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("Итоговый ответ"),
      ),
    )

    val events = orchestrator(llm, toolRegistry).executeNim("c1", "запиши заметку").toList()

    assertEquals("пишущее действие черновика не должно выполниться второй раз", 1, writeTool.executions)
    val reviseCall = events.filterIsInstance<AgentEvent.ToolCompleted>().last { it.name == "notes" }
    assertEquals("Tool 'notes' is not available in this context.", (reviseCall.result as? ToolResult.Error)?.message)

    // Call #3 (0-based) is the first round of the revise: its schemas have no writing tool, the read tool is there.
    assertEquals(listOf("lookup"), llm.seenToolNames[3])
    val reviseInstruction = llm.seenMessages[3].last { it.role == "user" }.content.orEmpty()
    assertTrue("правке сказано, что черновик уже сделал", reviseInstruction.contains("- notes — успешно"))
    assertTrue(events.filterIsInstance<AgentEvent.Completed>().isNotEmpty())
  }

  /**
   * The provider plays no part in these tests — the mechanics are checked, not the request's addressee.
   * Helpers, so that `LlmProvider.META` is not repeated in every call and does not give the
   * impression that the choice of vendor means anything here.
   */
  private fun ReflectionOrchestrator.executeNim(conversationId: String, message: String) =
    execute(conversationId, message, LlmProvider.META)

}
