package com.aiopenpocket.agent.core.skill

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

/**
 * [SkillRegistry] as a composite of two sources: the read-only built-in [Skill] beans (Metro) and
 * agent-owned custom skills persisted in Room via [UserSkillRepository]. Covers the registry
 * contract itself (built-in lookup, Room lookup, built-in-wins collision, id union), the v1
 * publish-time content rules ([validateUserSkill]: blank/oversized markdown, id colliding with a
 * built-in), and — end to end through the real [ReactLoopRunner] gate — that a custom agent can
 * `load_skill` its own custom skill but never another agent's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompositeSkillRegistryTest {

  private object NoopLog : com.aiopenpocket.agent.core.AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** Minimal built-in [Skill] fake — no [android.app.Application]/assets needed, unlike [AssetSkill]. */
  private class FakeBuiltInSkill(private val id: String) : Skill {
    override val manifest = SkillManifest(id = id, description = "built-in $id", whenToUse = "when $id")
    override suspend fun instructions(): String = "built-in-instructions:$id"
  }

  private lateinit var db: AppDatabase
  private lateinit var userSkillRepository: UserSkillRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      // Прямой исполнитель (запрос выполняется на вызывающем потоке), а не пул Room. Иначе suspend-DAO
      // уходит на чужой поток, тестовый диспетчер runTest остаётся без работы и промотывает ВИРТУАЛЬНОЕ
      // время вперёд — а `attemptLoadSkill` ниже гоняет настоящий ToolExecutor, у которого теперь есть
      // withTimeoutOrNull(TOOL_TIMEOUT_MS). Тул отрабатывал за микросекунды реального времени и всё
      // равно получал таймаут: 30 виртуальных секунд «проходили», пока Room работал на своём потоке.
      .setQueryExecutor(Runnable::run)
      .setTransactionExecutor(Runnable::run)
      .allowMainThreadQueries()
      .build()
    userSkillRepository = UserSkillRepository(db.userSkillDao())
  }

  @After
  fun tearDown() = db.close()

  private fun registry(vararg builtIn: Skill): SkillRegistry =
    SkillRegistry(builtIn.toSet(), userSkillRepository)

  private fun customSkill(
    id: String,
    agentId: String,
    instructions: String = "custom instructions for $id",
  ) = UserSkillEntity(
    id = id,
    agentId = agentId,
    name = id,
    description = "Custom skill $id",
    whenToUse = "When $id is relevant",
    instructionsMarkdown = instructions,
    createdAt = 0L,
    updatedAt = 0L,
  )

  private fun customAgent(id: String, skillIds: Set<String>) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Агент $id",
    description = "Тестовый кастомный агент.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент.",
    toolNames = setOf("load_skill"),
    skillIds = skillIds,
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  // --- Registry contract ---------------------------------------------------------------------

  @Test
  fun `get resolves a built-in skill`() = runTest {
    val skill = registry(FakeBuiltInSkill("budget")).get("budget")

    assertEquals("built-in-instructions:budget", skill?.instructions())
  }

  @Test
  fun `get resolves a Room-backed custom skill`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "my-skill", agentId = "agent-a"))

    val skill = registry().get("my-skill")

    assertEquals("custom instructions for my-skill", skill?.instructions())
  }

  @Test
  fun `get returns null when the id exists in neither source`() = runTest {
    assertNull(registry(FakeBuiltInSkill("budget")).get("nope"))
  }

  @Test
  fun `built-in wins a same-id collision with a Room custom skill`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "budget", agentId = "agent-a", instructions = "shadow attempt"))

    val skill = registry(FakeBuiltInSkill("budget")).get("budget")

    assertEquals("built-in-instructions:budget", skill?.instructions())
  }

  @Test
  fun `ids is the union of built-in and Room ids`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "my-skill", agentId = "agent-a"))

    assertEquals(setOf("budget", "my-skill"), registry(FakeBuiltInSkill("budget")).ids())
  }

  @Test
  fun `catalog scoped to agent A's skillIds does not surface agent B's custom skill`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "agent-a-skill", agentId = "agent-a"))
    db.userSkillDao().upsert(customSkill(id = "agent-b-skill", agentId = "agent-b"))

    val catalogForAgentA = registry().catalog(setOf("agent-a-skill"))

    assertTrue(catalogForAgentA.contains("agent-a-skill"))
    assertTrue("agent A must not see agent B's custom skill in its catalog", !catalogForAgentA.contains("agent-b-skill"))
  }

  // --- validateUserSkill (v1 publish-time content rules) -------------------------------------

  @Test
  fun `blank custom skill instructions fail validation`() {
    val issues = validateUserSkill(customSkill(id = "empty", agentId = "agent-a", instructions = "   "))

    assertTrue(issues.any { it.code == "blank_skill_instructions" })
  }

  @Test
  fun `oversized custom skill instructions fail validation`() {
    val oversized = customSkill(
      id = "too-big",
      agentId = "agent-a",
      instructions = "x".repeat(AgentValidator.MAX_CUSTOM_SKILL_CHARS + 1),
    )

    val issues = validateUserSkill(oversized)

    assertTrue(issues.any { it.code == "skill_instructions_too_long" })
  }

  @Test
  fun `custom skill id colliding with a built-in fails validation`() {
    val issues = validateUserSkill(customSkill(id = "budget", agentId = "agent-a"))

    assertTrue(issues.any { it.code == "skill_id_collision" })
  }

  @Test
  fun `well-formed custom skill has no issues`() {
    assertTrue(validateUserSkill(customSkill(id = "my-skill", agentId = "agent-a")).isEmpty())
  }

  // --- End to end through the real ReactLoopRunner gate --------------------------------------

  /** LLM that always requests `load_skill(requestedId)` on its first call, then answers with text. */
  private class LoadSkillLlm(private val requestedId: String) : LlmClient {
    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      val alreadyAsked = messages.any { it.role == "tool" }
      return if (!alreadyAsked) {
        flowOf(
          StreamEvent.ToolCall("call-1", "load_skill", mapOf("id" to JsonPrimitive(requestedId))),
          StreamEvent.Finish("tool_calls"),
        )
      } else {
        flowOf(StreamEvent.TextDelta("done"), StreamEvent.Finish("stop"))
      }
    }
  }

  private suspend fun attemptLoadSkill(requestedId: String, agent: AgentDefinition): ToolResult {
    val loadSkillTool = LoadSkillTool(registry())
    val toolRegistry = ToolRegistry(setOf(loadSkillTool))
    val runner = ReactLoopRunner(
      LoadSkillLlm(requestedId),
      ToolExecutor(toolRegistry),
      toolRegistry,
      MemoryRepository(db.factDao(), db.episodeDao()),
      NoopLog,
      AgentHookRepository(db.agentHookDao()),
      ToolApprovalCoordinator(),
    )
    val result = runner.run(
      conversationId = "c1",
      messages = mutableListOf(ChatMessage(role = "user", content = "please load $requestedId")),
      tools = toolRegistry.getAllSchemas(),
      provider = LlmProvider.OPENROUTER,
      agent = agent,
    ) { }
    return result.toolTrace.single { it.toolName == "load_skill" }.result
  }

  @Test
  fun `agent can load_skill its own custom skill through the real gate`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "agent-a-skill", agentId = "agent-a"))
    val agentA = customAgent(id = "agent-a", skillIds = setOf("agent-a-skill"))

    val result = attemptLoadSkill("agent-a-skill", agentA)

    assertEquals(ToolResult.Success("custom instructions for agent-a-skill"), result)
  }

  @Test
  fun `agent A cannot load_skill agent B's custom skill through the real gate`() = runTest {
    db.userSkillDao().upsert(customSkill(id = "agent-a-skill", agentId = "agent-a"))
    db.userSkillDao().upsert(customSkill(id = "agent-b-skill", agentId = "agent-b"))
    val agentA = customAgent(id = "agent-a", skillIds = setOf("agent-a-skill"))

    val result = attemptLoadSkill("agent-b-skill", agentA)

    assertTrue(result is ToolResult.Error)
  }
}
