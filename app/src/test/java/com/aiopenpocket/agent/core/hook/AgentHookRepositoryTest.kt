package com.aiopenpocket.agent.core.hook

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
 * [AgentHookRepository] round-tripped through a real (in-memory) Room database — the ordering
 * [getForAgent] promises (mirrors [HookEngine]'s own `(priority, id)` tie-break), the wholesale
 * [AgentHookRepository.replaceForAgent] semantics [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish]
 * relies on, and the `agent_hook_runs` journal's 100-row-per-hook retention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentHookRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: AgentHookRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = AgentHookRepository(db.agentHookDao())
  }

  @After
  fun tearDown() = db.close()

  private fun hook(id: String, agentId: String = "agent-1", priority: Int = 0) = AgentHook(
    id = id,
    agentId = agentId,
    event = HookEvent.BEFORE_TOOL,
    conditionType = "always",
    conditionJson = "{}",
    actionType = HookActionType.LOG,
    actionJson = "{}",
    priority = priority,
    enabled = true,
  )

  private fun runRecord(hookId: String, timestamp: Long) = AgentHookRunEntity(
    id = "run-$hookId-$timestamp",
    hookId = hookId,
    conversationId = "c1",
    runId = "r1",
    timestamp = timestamp,
    status = "LOG",
    summary = "",
    errorMessage = null,
  )

  @Test
  fun `getForAgent returns hooks sorted by priority then id`() = runTest {
    repository.replaceForAgent("agent-1", listOf(hook("b", priority = 1), hook("a", priority = 0)))

    assertEquals(listOf("a", "b"), repository.getForAgent("agent-1").map { it.id })
  }

  @Test
  fun `replaceForAgent fully replaces the previous set, not additive upsert`() = runTest {
    repository.replaceForAgent("agent-1", listOf(hook("old")))

    repository.replaceForAgent("agent-1", listOf(hook("new")))

    assertEquals(listOf("new"), repository.getForAgent("agent-1").map { it.id })
  }

  @Test
  fun `hooks are scoped per agent`() = runTest {
    repository.replaceForAgent("agent-1", listOf(hook("h1", agentId = "agent-1")))
    repository.replaceForAgent("agent-2", listOf(hook("h2", agentId = "agent-2")))

    assertEquals(listOf("h1"), repository.getForAgent("agent-1").map { it.id })
    assertEquals(listOf("h2"), repository.getForAgent("agent-2").map { it.id })
  }

  @Test
  fun `getOwnerAgentId reflects the current owner, null when the id was never used`() = runTest {
    assertNull(repository.getOwnerAgentId("nope"))

    repository.replaceForAgent("agent-1", listOf(hook("h1")))

    assertEquals("agent-1", repository.getOwnerAgentId("h1"))
  }

  @Test
  fun `recordRun keeps only the most recent 100 runs per hook`() = runTest {
    repeat(105) { i -> repository.recordRun(runRecord("h1", timestamp = i.toLong())) }

    val runs = repository.getRunsForHook("h1")

    assertEquals(100, runs.size)
    // Oldest 5 (timestamps 0..4) trimmed away; most recent (104) first, since getRunsForHook orders newest-first.
    assertEquals(104L, runs.first().timestamp)
    assertTrue("the oldest 5 runs must have been trimmed", runs.none { it.timestamp < 5L })
  }

  @Test
  fun `recordRun trims independently per hook - one hook's history never evicts another's`() = runTest {
    repeat(105) { i -> repository.recordRun(runRecord("h1", timestamp = i.toLong())) }
    repository.recordRun(runRecord("h2", timestamp = 0L))

    assertEquals(100, repository.getRunsForHook("h1").size)
    assertEquals(1, repository.getRunsForHook("h2").size)
  }
}
