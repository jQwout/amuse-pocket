package com.aiopenpocket.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of CHAT/TUTOR turns ([resolveTurnExecutionPlan]). PLAN/GOAL go through the Structured Plan
 * Engine ([StructuredPlanOrchestrator]) and never reach this function — their own routing is covered by
 * the dispatch of `com.aiopenpocket.di.TurnModule` (see the [AgentMode] kdoc) and
 * `StructuredPlanOrchestratorTest`/`PlanRunnerTest`/`PlanPlannerTest`.
 *
 * Task 15 (structured-plan-engine-907066) removed from here the tests of the old prompt-only plan gate and
 * goal loop ([resolveTurnExecutionPlan] no longer takes `planApproved`/`goalMaxIterations` and does not pick
 * a `PromptMode` — both mechanisms belonged to the withdrawn PLAN/GOAL prompt contract).
 */
class AgentModeTest {

  private val chatMax = 10

  private fun plan(mode: AgentMode) = resolveTurnExecutionPlan(mode, chatMaxIterations = chatMax)

  @Test
  fun chat_usesAllToolsFullIterationsNoSuffix() {
    val turn = plan(AgentMode.CHAT)
    assertTrue(turn.usesTools)
    assertEquals(chatMax, turn.maxIterations)
    assertNull(turn.systemPromptSuffix)
  }

  @Test
  fun tutor_blocksToolsSingleIterationWithTutorSuffix() {
    // A question to the LLM during a lesson: the tutor only EXPLAINS — no tools, one round, and the frame
    // "explain and stop, do not continue the lesson".
    val turn = plan(AgentMode.TUTOR)
    assertFalse("tutor-ход только объясняет — инструменты отключены", turn.usesTools)
    assertEquals(1, turn.maxIterations)
    assertTrue("tutor несёт рамку «только объясняй»", turn.systemPromptSuffix!!.contains("объясни"))
  }
}
