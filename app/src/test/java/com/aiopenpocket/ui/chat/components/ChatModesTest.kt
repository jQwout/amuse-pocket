package com.aiopenpocket.ui.chat.components

// @trace structured-plan-engine-907066
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.loop.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat mode selector must reflect reality: PLAN/GOAL only where the planner can run (an agent-bound
 * chat), AND only the modes the builder let the user allow ([AgentDefinition.allowedModes]) — otherwise
 * a user who restricted their agent still sees chips the runtime would run anyway. Pure function so the
 * selector just renders what it returns.
 */
class ChatModesTest {

  private fun agent(vararg allowed: AgentMode) = AgentDefinition(
    id = "a", source = AgentSource.USER, name = "A", description = "d", iconKey = "sparkles",
    systemPrompt = "s", toolNames = emptySet(), skillIds = emptySet(),
    defaultMode = AgentMode.CHAT, allowedModes = allowed.toSet(),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:a:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(), hooks = emptyList(), configVersion = 1L, status = AgentStatus.PUBLISHED,
  )

  @Test
  fun `a plain chat (no bound agent) offers only Chat and Reflect, never Plan or Goal`() {
    assertEquals(listOf(AgentMode.CHAT, AgentMode.REFLECT), availableModesFor(agent = null))
  }

  @Test
  fun `an agent allowing every mode offers Chat, Plan, Goal and Reflect in display order`() {
    assertEquals(
      listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT),
      availableModesFor(agent(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT)),
    )
  }

  @Test
  fun `an agent restricted to Chat and Plan does not get Goal or Reflect`() {
    assertEquals(listOf(AgentMode.CHAT, AgentMode.PLAN), availableModesFor(agent(AgentMode.CHAT, AgentMode.PLAN)))
  }

  @Test
  fun `Chat is always offered even when an agent's allowedModes omits it`() {
    assertEquals(listOf(AgentMode.CHAT, AgentMode.GOAL), availableModesFor(agent(AgentMode.GOAL)))
  }
}
