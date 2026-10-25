package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.mcp.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт результата хода: blank-ошибки, извлечение render_card.
 *
 * Task 15 (structured-plan-engine-907066) убрал отсюда тесты NEED_INFO-гейта план-мода и
 * GOAL-фолбэка пустого ответа на лимите витков — обе механики принадлежали снятому prompt-only
 * PLAN/GOAL (см. TurnCompletion.kt kdoc); [resolveCompletedTurn] больше не принимает `mode`/
 * `planApproved` и обслуживает CHAT/REFLECT/TUTOR одинаково, без специальных случаев.
 *
 * До Task 5 (structured-plan-engine-907066) [CompletedTurn] нёс ещё `planAwaitingApproval` — с тех
 * пор убран (см. TurnCompletion.kt kdoc): показан ли план и ждёт ли решения, теперь состояние
 * PlanRepository, а не производная одного хода.
 */
class TurnCompletionTest {

  private fun result(text: String, trace: List<ToolTrace> = emptyList()) =
    AgentResult(text = text, toolTrace = trace, state = AgentState.FINISHED, iterations = 1)

  private fun card(spec: String) =
    ToolTrace(iteration = 1, toolName = "render_card", arguments = emptyMap(), result = ToolResult.Success(spec))

  @Test
  fun `обычный ответ проходит как есть`() {
    val out = resolveCompletedTurn(result("Привет!"))
    assertEquals("Привет!", out.responseText)
    assertNull(out.blankError)
  }

  @Test
  fun `render_card извлекаются в cardSpecs по порядку`() {
    val out = resolveCompletedTurn(result("текст", listOf(card("{a}"), card("{b}"))))
    assertEquals(listOf("{a}", "{b}"), out.cardSpecs)
  }

  @Test
  fun `card-only ответ — не пустой, ошибки нет`() {
    val out = resolveCompletedTurn(result("", listOf(card("{a}"))))
    assertNull(out.blankError)
    assertEquals(listOf("{a}"), out.cardSpecs)
  }

  @Test
  fun `пустой ответ — blank-ошибка`() {
    // canRetry-решение живёт в TurnManager (blankError != null && есть текст пользователя),
    // покрыто TurnManagerTest; здесь — только чистая политика: пустой ответ → blank-ошибка.
    val out = resolveCompletedTurn(result(""))
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, out.blankError)
  }

  @Test
  fun `лимит шагов виден пользователю после преамбул`() {
    val out = resolveCompletedTurn(result("Сейчас посмотрю заметки").copy(hitIterationLimit = true, iterations = 10))
    assertEquals("Сейчас посмотрю заметки\n\n${TurnTexts.ITERATION_LIMIT_NOTE}", out.responseText)
    assertNull(out.blankError)
  }

  @Test
  fun `лимит шагов без текста — не «пустой ответ», а пометка о лимите`() {
    // Раньше: последний виток — только tool_call, преамбул нет, и пользователь видел «пустой ответ».
    val out = resolveCompletedTurn(result("").copy(hitIterationLimit = true, iterations = 10))
    assertEquals(TurnTexts.ITERATION_LIMIT_NOTE, out.responseText)
    assertNull(out.blankError)
  }
}
