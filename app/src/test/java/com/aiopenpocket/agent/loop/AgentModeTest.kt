package com.aiopenpocket.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт CHAT/TUTOR-ходов ([resolveTurnExecutionPlan]). PLAN/GOAL идут через Structured Plan
 * Engine ([StructuredPlanOrchestrator]) и никогда не доходят до этой функции — их собственная
 * маршрутизация покрыта `com.aiopenpocket.di.TurnModule`'s dispatch (см. [AgentMode] kdoc) и
 * `StructuredPlanOrchestratorTest`/`PlanRunnerTest`/`PlanPlannerTest`.
 *
 * Task 15 (structured-plan-engine-907066) убрал отсюда тесты старого prompt-only план-гейта и
 * гоал-петли ([resolveTurnExecutionPlan] больше не принимает `planApproved`/`goalMaxIterations` и не
 * выбирает `PromptMode` — обе механики принадлежали снятому PLAN/GOAL prompt-контракту).
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
    // Вопрос к LLM во время урока: репетитор только ОБЪЯСНЯЕТ — никаких инструментов, один виток,
    // и рамка «объясни и остановись, не продолжай урок».
    val turn = plan(AgentMode.TUTOR)
    assertFalse("tutor-ход только объясняет — инструменты отключены", turn.usesTools)
    assertEquals(1, turn.maxIterations)
    assertTrue("tutor несёт рамку «только объясняй»", turn.systemPromptSuffix!!.contains("объясни"))
  }
}
