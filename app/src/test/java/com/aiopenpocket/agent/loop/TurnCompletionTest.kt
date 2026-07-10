package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.mcp.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Контракт результата хода: NEED_INFO, план-бар, blank-ошибки, GOAL-фолбэк, render_card. */
class TurnCompletionTest {

  private fun result(text: String, trace: List<ToolTrace> = emptyList()) =
    AgentResult(text = text, toolTrace = trace, state = AgentState.FINISHED, iterations = 1)

  private fun card(spec: String) =
    ToolTrace(iteration = 1, toolName = "render_card", arguments = emptyMap(), result = ToolResult.Success(spec))

  private fun resolve(
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    res: AgentResult,
    hasRetryContext: Boolean = true,
  ) = resolveCompletedTurn(mode, planApproved, res, hasRetryContext)

  @Test
  fun `обычный ответ проходит как есть`() {
    val out = resolve(res = result("Привет!"))
    assertEquals("Привет!", out.responseText)
    assertNull(out.blankError)
    assertFalse(out.canRetry)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `render_card извлекаются в cardSpecs по порядку`() {
    val out = resolve(res = result("текст", listOf(card("{a}"), card("{b}"))))
    assertEquals(listOf("{a}", "{b}"), out.cardSpecs)
  }

  @Test
  fun `card-only ответ — не пустой, ошибки нет`() {
    val out = resolve(res = result("", listOf(card("{a}"))))
    assertNull(out.blankError)
    assertFalse(out.canRetry)
  }

  @Test
  fun `пустой ответ в чате — blank-ошибка, ретрай при наличии контекста`() {
    val out = resolve(res = result(""))
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, out.blankError)
    assertTrue(out.canRetry)
    assertFalse(resolve(res = result(""), hasRetryContext = false).canRetry)
  }

  @Test
  fun `план-фаза - непустой план поднимает план-бар и триммится`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("  1. шаг\n"))
    assertEquals("1. шаг", out.responseText)
    assertTrue(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - NEED_INFO вырезается и НЕ поднимает план-бар`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]] Какой отчёт нужен?"))
    assertEquals("Какой отчёт нужен?", out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - NEED_INFO с пустым остатком даёт фолбэк-вопрос`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]]"))
    assertEquals(TurnTexts.NEED_INFO_FALLBACK_QUESTION, out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - пустой ответ даёт план-ошибку`() {
    val out = resolve(mode = AgentMode.PLAN, res = result(""))
    assertEquals(TurnTexts.BLANK_PLAN_ERROR, out.blankError)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `одобренный план обрабатывается как обычный чат`() {
    val out = resolve(mode = AgentMode.PLAN, planApproved = true, res = result("[[NEED_INFO]] х"))
    // После одобрения маркер не интерпретируется — фаза планирования закончилась.
    assertEquals("[[NEED_INFO]] х", out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `гоал-мод - пустой ответ на лимите заменяется честным частичным итогом`() {
    val out = resolve(mode = AgentMode.GOAL, res = result(""))
    assertEquals(TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL, out.responseText)
    assertNull("непустой фолбэк — это не blank-ошибка", out.blankError)
  }
}
