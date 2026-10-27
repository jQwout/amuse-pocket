package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnContextHolderTest {

  /** Minimal valid [AgentDefinition] — only [AgentDefinition.id] matters to these tests. */
  private fun agent(id: String) = AgentDefinition(
    id = id,
    source = AgentSource.PRESET,
    name = id,
    description = "test agent",
    iconKey = "sparkles",
    systemPrompt = "test",
    toolNames = emptySet(),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  @Test
  fun `empty by default`() {
    val holder = TurnContextHolder()
    assertNull(holder.conversationId)
    assertNull(holder.agent)
    assertNull(holder.agentId)
  }

  @Test
  fun `set seeds current thread`() {
    val holder = TurnContextHolder()
    val reminders = agent("reminders")
    holder.set("conv1", reminders)
    assertEquals("conv1", holder.conversationId)
    assertEquals(reminders, holder.agent)
    // Convenience accessor for callers (e.g. HeartbeatTool) that only need the id.
    assertEquals("reminders", holder.agentId)
  }

  // A guarantee against a race: TurnManager runs turns of different conversations in parallel. Each turn
  // in its own contextElement wrapper must see ITS OWN conversationId/agent, even after hopping to another thread.
  @Test
  fun `contextElement isolates concurrent turns`() = runTest {
    val holder = TurnContextHolder()
    val budget = agent("budget")
    val diary = agent("diary")
    val results = listOf("A" to budget, "B" to diary).map { (conv, agentDef) ->
      async(Dispatchers.Default) {
        withContext(holder.contextElement(conv, agentDef)) {
          yield() // provoke a possible thread hop inside the turn
          holder.conversationId to holder.agent?.id
        }
      }
    }.awaitAll()
    assertEquals(listOf("A" to "budget", "B" to "diary"), results)
    assertNull(holder.conversationId) // outside the wrapper the context is empty again
    assertNull(holder.agent)
  }
}
