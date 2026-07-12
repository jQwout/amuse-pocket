package com.aiopenpocket.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Контракт режимов: гейт план-мода и петля гоал-мода. */
class AgentModeTest {

  private val chatMax = 10

  private fun plan(mode: AgentMode, approved: Boolean = false, goalMax: Int = 3) =
    resolveTurnExecutionPlan(mode, planApproved = approved, goalMaxIterations = goalMax, chatMaxIterations = chatMax)

  @Test
  fun chat_usesAllToolsFullIterationsNoModePrompt() {
    val turn = plan(AgentMode.CHAT)
    assertTrue(turn.usesTools)
    assertEquals(chatMax, turn.maxIterations)
    assertEquals(PromptMode.NONE, turn.promptMode)
  }

  @Test
  fun planPlanning_blocksToolsSingleIteration() {
    val turn = plan(AgentMode.PLAN, approved = false)
    assertFalse("в фазе планирования инструменты должны быть отключены (гейт)", turn.usesTools)
    assertEquals(1, turn.maxIterations)
    assertEquals(PromptMode.PLAN_PLANNING, turn.promptMode)
  }

  @Test
  fun planApproved_enablesToolsAndExecutingPrompt() {
    val turn = plan(AgentMode.PLAN, approved = true)
    assertTrue(turn.usesTools)
    assertEquals(chatMax, turn.maxIterations)
    assertEquals(PromptMode.PLAN_EXECUTING, turn.promptMode)
  }

  @Test
  fun goal_usesToolsLimitedToGoalIterations() {
    val turn = plan(AgentMode.GOAL, goalMax = 3)
    assertTrue(turn.usesTools)
    assertEquals(3, turn.maxIterations)
    assertEquals(PromptMode.GOAL, turn.promptMode)
  }

  @Test
  fun goal_iterationLimitIsClampedToChatMax() {
    assertEquals(chatMax, plan(AgentMode.GOAL, goalMax = 99).maxIterations)
    assertEquals(1, plan(AgentMode.GOAL, goalMax = 0).maxIterations)
  }

  @Test
  fun goal_promptSuffixNamesTheIterationBudget() {
    val turn = plan(AgentMode.GOAL, goalMax = 3)
    assertTrue("суффикс должен называть бюджет витков", turn.systemPromptSuffix!!.contains("не более 3 витков"))
  }

  @Test
  fun nonGoalModes_haveNoPromptSuffix() {
    assertNull(plan(AgentMode.CHAT).systemPromptSuffix)
    assertNull(plan(AgentMode.PLAN, approved = false).systemPromptSuffix)
    assertNull(plan(AgentMode.PLAN, approved = true).systemPromptSuffix)
  }

  @Test
  fun tutor_blocksToolsSingleIterationWithTutorSuffix() {
    // Вопрос к LLM во время урока: репетитор только ОБЪЯСНЯЕТ — никаких инструментов, один виток,
    // и рамка «объясни и остановись, не продолжай урок».
    val turn = plan(AgentMode.TUTOR)
    assertFalse("tutor-ход только объясняет — инструменты отключены", turn.usesTools)
    assertEquals(1, turn.maxIterations)
    assertEquals(PromptMode.NONE, turn.promptMode)
    assertTrue("tutor несёт рамку «только объясняй»", turn.systemPromptSuffix!!.contains("объясни"))
  }
}
