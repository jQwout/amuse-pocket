package com.aiopenpocket.agent.core.storage

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
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
 * [AgentStorageRepository] round-tripped through a real (in-memory) Room database (Part B of the
 * database_query security hardening, `user-agent-builder-848153`) - mirrors
 * [com.aiopenpocket.agent.core.hook.AgentHookRepositoryTest]'s structure. Covers the persistence layer
 * itself: per-key upsert, get, collection listing (with and without a key-prefix filter), delete and
 * deleteByAgent. This repository trusts whatever `agentId` it is passed directly (no
 * [com.aiopenpocket.agent.loop.TurnContextHolder] involved here) - the actual agent-isolation
 * GUARANTEE (an agent physically cannot address another agent's storage) is enforced one layer up, in
 * `StorageTool`, and is covered separately by `StorageToolTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentStorageRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: AgentStorageRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = AgentStorageRepository(db.agentStorageDao())
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `put then get round-trips the exact value`() = runTest {
    repository.put("agent-1", "notes", "k1", """{"text":"hello"}""")

    val entry = repository.get("agent-1", "notes", "k1")

    assertEquals("""{"text":"hello"}""", entry?.valueJson)
  }

  @Test
  fun `get on a missing key returns null`() = runTest {
    assertNull(repository.get("agent-1", "notes", "nope"))
  }

  @Test
  fun `put on an existing key upserts rather than duplicating`() = runTest {
    repository.put("agent-1", "notes", "k1", "\"first\"")
    repository.put("agent-1", "notes", "k1", "\"second\"")

    assertEquals("\"second\"", repository.get("agent-1", "notes", "k1")?.valueJson)
    assertEquals(1, repository.list("agent-1", "notes").size)
  }

  @Test
  fun `rows are scoped per agentId - same collection and key, different agents, do not collide`() = runTest {
    repository.put("agent-1", "notes", "k1", "\"mine\"")
    repository.put("agent-2", "notes", "k1", "\"theirs\"")

    assertEquals("\"mine\"", repository.get("agent-1", "notes", "k1")?.valueJson)
    assertEquals("\"theirs\"", repository.get("agent-2", "notes", "k1")?.valueJson)
  }

  @Test
  fun `list returns every entry in a collection sorted by key`() = runTest {
    repository.put("agent-1", "notes", "b", "\"2\"")
    repository.put("agent-1", "notes", "a", "\"1\"")
    repository.put("agent-1", "other", "z", "\"ignored\"")

    val entries = repository.list("agent-1", "notes")

    assertEquals(listOf("a", "b"), entries.map { it.key })
  }

  @Test
  fun `list with a key-prefix only returns matching keys`() = runTest {
    repository.put("agent-1", "notes", "todo_1", "\"a\"")
    repository.put("agent-1", "notes", "todo_2", "\"b\"")
    repository.put("agent-1", "notes", "other", "\"c\"")

    val entries = repository.list("agent-1", "notes", keyPrefix = "todo_")

    assertEquals(setOf("todo_1", "todo_2"), entries.map { it.key }.toSet())
  }

  @Test
  fun `delete removes exactly the targeted row`() = runTest {
    repository.put("agent-1", "notes", "k1", "\"a\"")
    repository.put("agent-1", "notes", "k2", "\"b\"")

    repository.delete("agent-1", "notes", "k1")

    assertNull(repository.get("agent-1", "notes", "k1"))
    assertEquals("\"b\"", repository.get("agent-1", "notes", "k2")?.valueJson)
  }

  @Test
  fun `delete on a non-existent key is a harmless no-op`() = runTest {
    repository.delete("agent-1", "notes", "never-existed")
    // No exception; nothing to assert beyond "did not throw".
  }

  @Test
  fun `deleteByAgent wipes every collection for that agent only`() = runTest {
    repository.put("agent-1", "notes", "k1", "\"a\"")
    repository.put("agent-1", "counters", "k2", "\"b\"")
    repository.put("agent-2", "notes", "k1", "\"untouched\"")

    repository.deleteByAgent("agent-1")

    assertTrue(repository.list("agent-1", "notes").isEmpty())
    assertTrue(repository.list("agent-1", "counters").isEmpty())
    assertEquals("\"untouched\"", repository.get("agent-2", "notes", "k1")?.valueJson)
  }
}
