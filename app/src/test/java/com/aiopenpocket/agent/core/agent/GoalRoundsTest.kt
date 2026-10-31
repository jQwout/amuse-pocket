package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.loop.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The goal-mode round limit moved from Settings onto the agent (the owner, 31 Oct 2026). These pin what a
 * chat reads: the agent's own number, 3 for a plain chat or an unset agent, and never a value outside 1..10.
 */
class GoalRoundsTest {

  private fun agent(goalMaxRounds: Int?) = AgentDefinition(
    id = "my-goal-agent",
    source = AgentSource.USER,
    name = "Goal agent",
    description = "d",
    iconKey = "",
    systemPrompt = "p",
    toolNames = emptySet(),
    skillIds = emptySet(),
    defaultMode = AgentMode.GOAL,
    allowedModes = setOf(AgentMode.CHAT, AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:my-goal-agent:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    goalMaxRounds = goalMaxRounds,
    configVersion = 0L,
    status = AgentStatus.DRAFT,
  )

  @Test
  fun `a plain chat with no agent runs the default 3 rounds`() {
    assertEquals(3, goalRoundsFor(null))
  }

  @Test
  fun `an agent that sets no limit runs the default`() {
    assertEquals(3, goalRoundsFor(agent(null)))
  }

  @Test
  fun `an agent's own limit is what the chat uses`() {
    assertEquals(7, goalRoundsFor(agent(7)))
    assertEquals(1, goalRoundsFor(agent(1)))
    assertEquals(10, goalRoundsFor(agent(10)))
  }

  /** A row written by a newer or hand-edited build must not give GOAL zero rounds or a runaway loop. */
  @Test
  fun `a stored value outside 1 to 10 is clamped`() {
    assertEquals(1, goalRoundsFor(agent(0)))
    assertEquals(1, goalRoundsFor(agent(-4)))
    assertEquals(10, goalRoundsFor(agent(99)))
  }

  @Test
  fun `the validator refuses a limit outside 1 to 10 and accepts the bounds`() {
    assertEquals(listOf("goal_rounds_out_of_range"), AgentValidator.validate(validAgent(0)).map { it.code })
    assertEquals(listOf("goal_rounds_out_of_range"), AgentValidator.validate(validAgent(11)).map { it.code })
    assertEquals(emptyList<String>(), AgentValidator.validate(validAgent(1)).map { it.code })
    assertEquals(emptyList<String>(), AgentValidator.validate(validAgent(10)).map { it.code })
    assertEquals(emptyList<String>(), AgentValidator.validate(validAgent(null)).map { it.code })
  }

  private fun validAgent(goalMaxRounds: Int?) = agent(goalMaxRounds).copy(iconKey = "sparkles")
}
