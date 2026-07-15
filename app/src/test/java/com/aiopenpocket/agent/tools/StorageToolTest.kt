package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.storage.AgentStorageRepository
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
 * Security regression for `storage_tool`'s per-agent isolation (Part B of the database_query security
 * hardening, `user-agent-builder-848153`) - mirrors [MemoryToolsNamespaceTest]'s framing exactly: the
 * `agentId` every call is scoped under comes ONLY from [TurnContextHolder] - never a tool argument -
 * so a bound agent physically cannot address another agent's storage no matter what its
 * `collection`/`key` arguments say. Also covers the value/label size caps, which exist only at this
 * tool layer (the repository underneath enforces none of them).
 *
 * Runs against a real in-memory Room DB (like [com.aiopenpocket.agent.core.agent.AgentResolverTest]/
 * [MemoryToolsNamespaceTest]) rather than a fake repository, so the isolation is proven at the actual
 * SQL/DAO layer, not just re-asserted against a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageToolTest {

  private lateinit var db: AppDatabase
  private lateinit var storageRepository: AgentStorageRepository
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: StorageTool

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    storageRepository = AgentStorageRepository(db.agentStorageDao())
    holder = TurnContextHolder()
    tool = StorageTool(storageRepository, holder)
  }

  @After
  fun tearDown() = db.close()

  private fun agent(id: String) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент $id",
    description = "Кастомный агент для проверки изоляции хранилища.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("storage_tool"),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun put(collection: String, key: String, value: String) =
    args("action" to "put", "collection" to collection, "key" to key, "value" to value)

  private fun get(collection: String, key: String) =
    args("action" to "get", "collection" to collection, "key" to key)

  private fun list(collection: String, keyPrefix: String? = null): Map<String, JsonElement> {
    val base = mutableListOf("action" to "list", "collection" to collection)
    keyPrefix?.let { base += "key_prefix" to it }
    return args(*base.toTypedArray())
  }

  private fun delete(collection: String, key: String) =
    args("action" to "delete", "collection" to collection, "key" to key)

  @Test
  fun `put then get round-trips scoped by the bound agent`() = runTest {
    holder.set("conv1", agent("my-agent"))

    tool.execute(put("notes", "k1", """{"text":"hello"}"""))
    val result = tool.execute(get("notes", "k1"))

    assertEquals("""{"text":"hello"}""", (result as ToolResult.Success).data)
  }

  @Test
  fun `a different context-agent cannot see or overwrite the first agent's rows`() = runTest {
    holder.set("conv-other", agent("agent-other"))
    tool.execute(put("notes", "k1", "\"other-secret\""))

    holder.set("conv-mine", agent("agent-mine"))
    tool.execute(put("notes", "k1", "\"my-secret\""))

    // My own read sees my own value.
    val mine = tool.execute(get("notes", "k1"))
    assertEquals("\"my-secret\"", (mine as ToolResult.Success).data)

    // My list only ever shows my own row, never the other agent's.
    val myListing = tool.execute(list("notes")).toString()
    assertTrue(myListing.contains("my-secret"))
    assertFalse(myListing.contains("other-secret"))

    // The decisive check: switching BACK to the other agent's context still shows ITS original
    // value, completely untouched by "agent-mine" writing the identical (collection, key) pair.
    // Without this read-back, the checks above alone cannot rule out both agents silently sharing
    // one row with last-write-wins semantics - that would also satisfy every assertion so far.
    holder.set("conv-other-2", agent("agent-other"))
    val other = tool.execute(get("notes", "k1"))
    assertEquals("\"other-secret\"", (other as ToolResult.Success).data)
  }

  @Test
  fun `plain chat (no bound agent) is isolated from every custom agent too`() = runTest {
    holder.set("conv-agent", agent("agent-1"))
    tool.execute(put("notes", "k1", "\"agent-secret\""))

    holder.set("conv-plain", null)
    tool.execute(put("notes", "k1", "\"plain-secret\""))

    val plainResult = tool.execute(get("notes", "k1"))
    assertEquals("\"plain-secret\"", (plainResult as ToolResult.Success).data)

    // Read-back under the CUSTOM agent's context again: still its own original value, not
    // clobbered by plain chat's later write to the same (collection, key) - the same decisive
    // check as the agent-vs-agent isolation test above, applied to the plain-chat namespace.
    holder.set("conv-agent-2", agent("agent-1"))
    val agentResult = tool.execute(get("notes", "k1"))
    assertEquals("\"agent-secret\"", (agentResult as ToolResult.Success).data)
  }

  @Test
  fun `plain chat storage is stable across calls (same default namespace every time)`() = runTest {
    holder.set("conv-a", null)
    tool.execute(put("notes", "k1", "\"value\""))

    holder.set("conv-b", null)
    val result = tool.execute(get("notes", "k1"))

    assertEquals("\"value\"", (result as ToolResult.Success).data)
  }

  @Test
  fun `list enumerates every entry in a collection`() = runTest {
    holder.set("conv1", agent("my-agent"))
    tool.execute(put("notes", "a", "\"1\""))
    tool.execute(put("notes", "b", "\"2\""))
    tool.execute(put("other-collection", "z", "\"ignored\""))

    val result = tool.execute(list("notes")).toString()

    assertTrue(result.contains("a: \"1\""))
    assertTrue(result.contains("b: \"2\""))
    assertFalse(result.contains("ignored"))
  }

  @Test
  fun `list with a key_prefix narrows the results`() = runTest {
    holder.set("conv1", agent("my-agent"))
    tool.execute(put("notes", "todo_1", "\"a\""))
    tool.execute(put("notes", "todo_2", "\"b\""))
    tool.execute(put("notes", "other", "\"c\""))

    val result = tool.execute(list("notes", keyPrefix = "todo_")).toString()

    assertTrue(result.contains("todo_1"))
    assertTrue(result.contains("todo_2"))
    assertFalse(result.contains("- other:"))
  }

  @Test
  fun `delete removes the entry`() = runTest {
    holder.set("conv1", agent("my-agent"))
    tool.execute(put("notes", "k1", "\"a\""))

    tool.execute(delete("notes", "k1"))
    val result = tool.execute(get("notes", "k1"))

    assertEquals("null", (result as ToolResult.Success).data)
  }

  @Test
  fun `delete under one agent cannot remove another agent's row`() = runTest {
    holder.set("conv-other", agent("agent-other"))
    tool.execute(put("notes", "k1", "\"other-secret\""))

    holder.set("conv-mine", agent("agent-mine"))
    tool.execute(delete("notes", "k1")) // no row of ours to delete - must not touch the other agent's

    holder.set("conv-other", agent("agent-other"))
    val result = tool.execute(get("notes", "k1"))
    assertEquals("\"other-secret\"", (result as ToolResult.Success).data)
  }

  @Test
  fun `put rejects a value over the size cap`() = runTest {
    holder.set("conv1", agent("my-agent"))
    val tooLong = "x".repeat(100_001)

    val result = tool.execute(put("notes", "k1", tooLong))

    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `put accepts a value exactly at the size cap`() = runTest {
    holder.set("conv1", agent("my-agent"))
    val atCap = "x".repeat(100_000)

    val result = tool.execute(put("notes", "k1", atCap))

    assertTrue(result is ToolResult.Success)
  }

  @Test
  fun `put rejects a collection label over the length cap`() = runTest {
    holder.set("conv1", agent("my-agent"))
    val tooLong = "c".repeat(129)

    val result = tool.execute(put(tooLong, "k1", "\"v\""))

    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `missing action argument is a clear error, not a crash`() = runTest {
    val result = tool.execute(args("collection" to "notes", "key" to "k1"))

    assertTrue(result is ToolResult.Error)
  }
}
