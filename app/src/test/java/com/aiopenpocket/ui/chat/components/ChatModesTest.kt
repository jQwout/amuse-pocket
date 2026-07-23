package com.aiopenpocket.ui.chat.components

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.loop.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plan/goal UI is reachable ONLY where the planner can actually run — i.e. an agent-bound chat
 * (the planner requires a resolvable agent). A plain chat (no agent) must NOT offer PLAN/GOAL, or the
 * user hits a dead-end (propose fails "requires a resolvable agent"). This pins that decision as a
 * pure function so [ModeSelector]/[com.aiopenpocket.ui.chat.ChatScreen] just render what it returns.
 */
class ChatModesTest {

  @Test
  fun `a plain chat (no bound agent) offers only Chat and Reflect, never Plan or Goal`() {
    assertEquals(listOf(AgentMode.CHAT, AgentMode.REFLECT), availableModesFor(agentId = null))
  }

  @Test
  fun `an agent-bound chat offers Chat, Plan, Goal and Reflect`() {
    assertEquals(
      listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT),
      availableModesFor(agentId = "agent-1"),
    )
  }
}
