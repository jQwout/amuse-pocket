package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security regression for per-agent memory isolation (Task 5 of the user-agent-builder plan): a
 * bound agent's `remember_fact`/`search_memory` must be confined to its `agent:{id}:` namespace IN
 * CODE — a system prompt says "don't read other agents' memory", but nothing stops a model from
 * ignoring that, so the enforcement has to live in [MemoryTools]/[MemoryRepository]/[FactDao],
 * not in prompt wording. Plain chat (no bound agent) must keep the exact global semantics it had
 * before this task — this is the one path the isolation must NOT touch.
 *
 * Runs against a real in-memory Room DB (like [com.aiopenpocket.agent.core.agent.AgentResolverTest])
 * rather than a fake repository, so the namespace scoping is proven at the actual SQL/DAO layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryToolsNamespaceTest {

  private lateinit var db: AppDatabase
  private lateinit var memoryRepository: MemoryRepository
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: MemoryTools

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    memoryRepository = MemoryRepository(db.factDao(), db.episodeDao())
    holder = TurnContextHolder()
    tool = MemoryTools(memoryRepository, holder)
  }

  @After
  fun tearDown() = db.close()

  private fun agent(id: String, includeSharedProfile: Boolean = false) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент $id",
    description = "Кастомный агент для проверки изоляции памяти.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("memory_tool"),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = includeSharedProfile),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun remember(key: String, value: String, category: String? = null): Map<String, JsonElement> {
    val base = mutableListOf("action" to "remember_fact", "key" to key, "value" to value)
    category?.let { base += "category" to it }
    return args(*base.toTypedArray())
  }

  private fun search(query: String) = args("action" to "search_memory", "query" to query)

  @Test
  fun `custom agent remember_fact namespaces the key`() = runTest {
    holder.set("conv1", agent("my-agent"))

    tool.execute(remember("note", "secret"))

    val saved = db.factDao().getByKey("agent:my-agent:note")
    assertEquals("secret", saved?.value)
    assertEquals("general", saved?.category)
    // Ключ БЕЗ пространства имён не создаётся — вся запись целиком под namespace агента.
    assertNull(db.factDao().getByKey("note"))
  }

  @Test
  fun `custom agent cannot read another agent's namespace via search_memory`() = runTest {
    holder.set("conv-other", agent("other"))
    tool.execute(remember("note", "other-secret"))

    holder.set("conv-mine", agent("my-agent"))
    tool.execute(remember("note", "my-secret"))

    // Прямой запрос по чужому namespace как строке поиска не должен вытащить чужой секрет.
    val crossNamespaceSearch = tool.execute(search("agent:other:")).toString()
    assertFalse(crossNamespaceSearch.contains("other-secret"))

    // Обычный поиск по своему же ключу видит СВОЙ факт и не видит чужой, даже если оба
    // совпадают по тексту запроса.
    val ownSearch = tool.execute(search("note")).toString()
    assertTrue(ownSearch.contains("my-secret"))
    assertFalse(ownSearch.contains("other-secret"))
  }

  @Test
  fun `plain chat keeps global key and honors requested category`() = runTest {
    holder.set("conv1", null)

    tool.execute(remember("user.city", "Berlin", category = "profile"))

    val saved = db.factDao().getByKey("user.city")
    assertEquals("Berlin", saved?.value)
    assertEquals("profile", saved?.category)
  }

  @Test
  fun `custom agent cannot force category=profile`() = runTest {
    holder.set("conv1", agent("my-agent"))

    tool.execute(remember("secretly_a_profile_fact", "x", category = "profile"))

    val saved = db.factDao().getByKey("agent:my-agent:secretly_a_profile_fact")
    assertEquals("general", saved?.category)
  }

  @Test
  fun `custom agent cannot force category=preference`() = runTest {
    holder.set("conv1", agent("my-agent"))

    tool.execute(remember("x", "y", category = "preference"))

    val saved = db.factDao().getByKey("agent:my-agent:x")
    assertEquals("general", saved?.category)
  }

  @Test
  fun `plain chat search_memory stays global exactly like before`() = runTest {
    holder.set("conv-a", agent("my-agent"))
    tool.execute(remember("note", "agent-secret"))

    holder.set("conv-b", null)
    tool.execute(remember("note", "plain-fact"))

    // Плоский чат (без агента) НЕ получил нового скоупа — видит и свой факт, и (как и раньше)
    // остальные ключи глобально. Именно этот путь задача обязана оставить нетронутым.
    val result = tool.execute(search("note")).toString()
    assertTrue(result.contains("plain-fact"))
    assertTrue(result.contains("agent-secret"))
  }

  @Test
  fun `repository-level scoped search never returns another namespace's facts`() = runTest {
    memoryRepository.saveFact("agent:mine:note", "mine-secret", "general")
    memoryRepository.saveFact("agent:other:note", "other-secret", "general")

    val scoped = memoryRepository.searchFactsInNamespace("agent:mine:", "note")

    assertEquals(listOf("mine-secret"), scoped.map { it.value })
  }

  // --- recall_episodes: только свой диалог, с потолком ----------------------------------------------

  private fun toolCallPayload(name: String, action: String) =
    """{"id":"1","name":"$name","risk":"READ","arguments":{"action":"$action"}}"""

  @Test
  fun `recall_episodes returns only the current conversation's episodes`() = runTest {
    memoryRepository.saveEpisode("tool_call", toolCallPayload("file_tool", "read"), "свой результат", conversationId = "conv-mine")
    memoryRepository.saveEpisode("tool_call", toolCallPayload("file_tool", "read"), "чужой результат", conversationId = "conv-other")
    holder.set("conv-mine", null)

    val result = tool.execute(args("action" to "recall_episodes", "range" to "today")).toString()

    assertTrue(result.contains("свой результат"))
    assertTrue("[file_tool read] — метка действия, а не голый tool_call", result.contains("[file_tool read]"))
    assertFalse("эпизод чужого диалога утёк в recall_episodes", result.contains("чужой результат"))
  }

  @Test
  fun `recall_episodes caps both the number of episodes and each outcome`() = runTest {
    repeat(30) {
      memoryRepository.saveEpisode("tool_call", toolCallPayload("storage", "get"), "Ж".repeat(5_000), conversationId = "conv1")
    }
    holder.set("conv1", null)

    val result = tool.execute(args("action" to "recall_episodes", "range" to "today")).toString()

    assertTrue("слишком много событий: ${result.lines().size}", result.lines().size <= 20)
    assertTrue("результат не обрезан: ${result.length} символов", result.length < 20 * 400)
  }

  @Test
  fun `getFactsByNamespace returns only that agent's facts`() = runTest {
    memoryRepository.saveFact("agent:mine:a", "1", "general")
    memoryRepository.saveFact("agent:mine:b", "2", "general")
    memoryRepository.saveFact("agent:other:a", "3", "general")

    val scoped = memoryRepository.getFactsByNamespace("agent:mine:")

    assertEquals(setOf("agent:mine:a", "agent:mine:b"), scoped.map { it.key }.toSet())
  }
}
