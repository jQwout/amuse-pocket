package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.mcp.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт результата хода: NEED_INFO, blank-ошибки, GOAL-фолбэк, render_card, REFLECT.
 *
 * До Task 5 (structured-plan-engine-907066) [CompletedTurn] нёс ещё `planAwaitingApproval` — с тех
 * пор убран (см. TurnCompletion.kt kdoc): показан ли план и ждёт ли решения, теперь состояние
 * PlanRepository, а не производная одного хода. Тесты фазы планирования (`план-фаза - ...`) ниже
 * по-прежнему покрывают responseText/blankError этой фазы — просто больше не проверяют убранный флаг.
 */
class TurnCompletionTest {

  private fun result(text: String, trace: List<ToolTrace> = emptyList(), hitLimit: Boolean = false) =
    AgentResult(text = text, toolTrace = trace, state = AgentState.FINISHED, iterations = 1, hitIterationLimit = hitLimit)

  private fun card(spec: String) =
    ToolTrace(iteration = 1, toolName = "render_card", arguments = emptyMap(), result = ToolResult.Success(spec))

  private fun resolve(
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    res: AgentResult,
  ) = resolveCompletedTurn(mode, planApproved, res)

  @Test
  fun `обычный ответ проходит как есть`() {
    val out = resolve(res = result("Привет!"))
    assertEquals("Привет!", out.responseText)
    assertNull(out.blankError)
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
    assertEquals(listOf("{a}"), out.cardSpecs)
  }

  @Test
  fun `пустой ответ в чате — blank-ошибка`() {
    // canRetry-решение живёт в TurnManager (blankError != null && есть текст пользователя),
    // покрыто TurnManagerTest; здесь — только чистая политика: пустой ответ → blank-ошибка.
    val out = resolve(res = result(""))
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, out.blankError)
  }

  @Test
  fun `план-фаза - непустой план триммится`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("  1. шаг\n"))
    assertEquals("1. шаг", out.responseText)
  }

  @Test
  fun `план-фаза - NEED_INFO вырезается`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]] Какой отчёт нужен?"))
    assertEquals("Какой отчёт нужен?", out.responseText)
  }

  @Test
  fun `план-фаза - NEED_INFO с пустым остатком даёт фолбэк-вопрос`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]]"))
    assertEquals(TurnTexts.NEED_INFO_FALLBACK_QUESTION, out.responseText)
  }

  @Test
  fun `план-фаза - пустой ответ даёт план-ошибку`() {
    val out = resolve(mode = AgentMode.PLAN, res = result(""))
    assertEquals(TurnTexts.BLANK_PLAN_ERROR, out.blankError)
  }

  @Test
  fun `одобренный план обрабатывается как обычный чат`() {
    val out = resolve(mode = AgentMode.PLAN, planApproved = true, res = result("[[NEED_INFO]] х"))
    // После одобрения маркер не интерпретируется — фаза планирования закончилась.
    assertEquals("[[NEED_INFO]] х", out.responseText)
  }

  @Test
  fun `гоал-мод - пустой ответ НА ЛИМИТЕ витков заменяется честным частичным итогом`() {
    val out = resolve(mode = AgentMode.GOAL, res = result("", hitLimit = true))
    assertEquals(TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL, out.responseText)
    assertNull("непустой фолбэк — это не blank-ошибка", out.blankError)
  }

  @Test
  fun `гоал-мод - card-only чистый финиш НЕ приписывает частичный итог`() {
    // Цикл завершился штатно (не лимит) карточкой без текста: показываем карточку, а не «частично».
    val out = resolve(mode = AgentMode.GOAL, res = result("", listOf(card("{a}")), hitLimit = false))
    assertEquals("", out.responseText)
    assertEquals(listOf("{a}"), out.cardSpecs)
    assertNull(out.blankError)
  }

  @Test
  fun `гоал-мод - пустой чистый финиш (не лимит) даёт обычную blank-ошибку, а не GOAL-итог`() {
    val out = resolve(mode = AgentMode.GOAL, res = result("", hitLimit = false))
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, out.blankError)
    assertFalse(out.responseText == TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL)
  }

  @Test
  fun `REFLECT обрабатывается как обычный чат — без GOAL-фолбэка`() {
    val out = resolve(mode = AgentMode.REFLECT, res = result("Итоговый ответ"))
    assertEquals("Итоговый ответ", out.responseText)
    assertNull(out.blankError)
  }
}
