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
 * Проверяет гейт пустого финала REFLECT (находка код-ревью): revise без текста и без карточки должен
 * дать терминальную ошибку + «Повторить», а не персиститься тремя декоративными заголовками
 * ✍/🔍/✨, которые делают transcript непустым и обошли бы blank-гейт resolveCompletedTurn.
 *
 * Настоящий [ReactLoopRunner] + [PrePromptAssembler] на in-memory Room; LLM — скриптовый фейк,
 * отдающий по одному потоку на вызов (draft → critique → revise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReflectionOrchestratorTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** Отдаёт по одному скрипту событий на каждый streamChat: [0]=draft, [1]=critique, [2]=revise. */
  private class ScriptedLlm(private val perCall: List<List<StreamEvent>>) : LlmClient {
    var calls = 0
      private set

    /** Снимок сообщений и имён тулов на каждый вызов — чтобы проверить, что увидела фаза правки. */
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

  /** Пишущий тул со счётчиком: доказывает, сколько раз действие выполнилось ПО-НАСТОЯЩЕМУ. */
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
      ConversationAnnotator(llm, conversationRepository) { LlmProvider.OPENROUTER },
      turnContextHolder,
      NoopLog,
    )
  }

  private fun textThenStop(text: String) = listOf(StreamEvent.TextDelta(text), StreamEvent.Finish("stop"))
  private val justStop = listOf(StreamEvent.Finish("stop"))

  @Test
  fun `пустой финал revise даёт Error и НЕ Completed`() = runTest {
    // draft и critique непусты, но revise возвращает пустоту — realistic «правка ничего не дала».
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
    // Реестр знает ТРИ тула (memory_tool + sms + email), но агент разрешает только memory_tool — до
    // фикса ReflectionOrchestrator отдавал draft/revise ПОЛНЫЙ registry.getAllSchemas() без учёта
    // agent, так что sms/email прошли бы как «доступные». Именно это и должен резать scoped tools +
    // agent-гейт — оба тула вне toolNames агента, и каждый должен быть отклонён НЕЗАВИСИМО в своей
    // фазе (sms в draft, email в revise), а не только в первой встреченной.
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

    // Черновик «галлюцинирует» вызов sms, а финальная правка — email: ОБА тула вне allowlist агента
    // (только memory_tool). Скриптовый LLM в тесте не смотрит на переданные ему схемы (как и любая
    // модель может унаследовать имя тула из истории или просто ошибиться) — именно поэтому проверка
    // обязана быть в рантайме цикла КАЖДОЙ фазы отдельно, а не только в том, что модели показали в draft.
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

    // Тот же гейт, но во ВТОРОЙ фазе (revise) — до фикса ReflectionOrchestrator scoped ровно draft, а
    // revise получал бы полный registry, так что этот вызов остался бы незамеченным regression'ом.
    val emailCall = events.filterIsInstance<AgentEvent.ToolCompleted>().single { it.name == "email" }
    val emailError = emailCall.result as? ToolResult.Error
    assertEquals("Tool 'email' is not available in this context.", emailError?.message)

    // Галлюцинация тула — не фатальная ошибка цикла; ход всё равно доходит до Completed.
    assertTrue(events.filterIsInstance<AgentEvent.Completed>().isNotEmpty())
  }

  @Test
  fun `REFLECT ход устанавливает контекст агента для memory_tool - remember_fact уходит в его namespace`() = runTest {
    // Task 5: до фикса ReflectionOrchestrator не оборачивал draft/revise в
    // turnContextHolder.contextElement(...), так что memory_tool внутри REFLECT-хода видел бы
    // holder.agent == null (глобальное пространство имён) даже для агента со своим namespace — тот
    // же класс дыры, что и REFLECT tool-scope до фикса в Task 4, но для памяти, а не allowlist тулов.
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
        // DRAFT итерация 1: модель вызывает remember_fact прямо в ходе черновика.
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
        // DRAFT итерация 2: финальный текст черновика (без него цикл продолжал бы крутиться).
        textThenStop("черновой ответ"),
        textThenStop("критика"),
        textThenStop("Итоговый ответ"),
      ),
    )

    orchestrator(llm, toolRegistry, turnContextHolder).executeNim("c1", "запомни note=secret").toList()

    val saved = db.factDao().getByKey("agent:my-agent:note")
    assertEquals("secret", saved?.value)
    assertEquals("general", saved?.category)
    // Без namespace ключ вообще не создаётся — не осталось «утечки» в глобальное пространство.
    assertNull(db.factDao().getByKey("note"))
  }

  @Test
  fun `правка не повторяет действия черновика - получает только тулы чтения и список уже сделанного`() = runTest {
    // Черновик в LIVE реально выполняет пишущий тул; раньше правка получала тот же набор тулов и
    // инструкцию «вызови нужный инструмент заново» — и действие выполнялось второй раз.
    val writeTool = CountingWriteTool("notes")
    val toolRegistry = ToolRegistry(setOf(writeTool, FakeTool("lookup")))
    val llm = ScriptedLlm(
      listOf(
        listOf(StreamEvent.ToolCall("call-1", "notes", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("черновой ответ"),
        textThenStop("критика"),
        // Правка «по старой привычке» зовёт тот же пишущий тул ещё раз.
        listOf(StreamEvent.ToolCall("call-2", "notes", emptyMap()), StreamEvent.Finish("tool_calls")),
        textThenStop("Итоговый ответ"),
      ),
    )

    val events = orchestrator(llm, toolRegistry).executeNim("c1", "запиши заметку").toList()

    assertEquals("пишущее действие черновика не должно выполниться второй раз", 1, writeTool.executions)
    val reviseCall = events.filterIsInstance<AgentEvent.ToolCompleted>().last { it.name == "notes" }
    assertEquals("Tool 'notes' is not available in this context.", (reviseCall.result as? ToolResult.Error)?.message)

    // Вызов №3 (0-based) — первый виток правки: пишущего тула в его схемах нет, тул чтения есть.
    assertEquals(listOf("lookup"), llm.seenToolNames[3])
    val reviseInstruction = llm.seenMessages[3].last { it.role == "user" }.content.orEmpty()
    assertTrue("правке сказано, что черновик уже сделал", reviseInstruction.contains("- notes — успешно"))
    assertTrue(events.filterIsInstance<AgentEvent.Completed>().isNotEmpty())
  }

  /**
   * Провайдер в этих тестах не участвует — проверяется механика, а не адресат запроса. Хелперы,
   * чтобы `LlmProvider.OPENROUTER` не повторялся в каждом вызове и не создавал впечатление, будто выбор
   * вендора здесь что-то значит.
   */
  private fun ReflectionOrchestrator.executeNim(conversationId: String, message: String) =
    execute(conversationId, message, LlmProvider.OPENROUTER)

}
