package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.mcp.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The turn result contract: blank errors, render_card extraction.
 *
 * Task 15 (structured-plan-engine-907066) removed from here the tests of the plan mode's NEED_INFO gate
 * and of the GOAL fallback for an empty answer at the round limit — both mechanisms belonged to the
 * withdrawn prompt-only PLAN/GOAL (see the TurnCompletion.kt kdoc); [resolveCompletedTurn] no longer takes
 * `mode`/`planApproved` and serves CHAT/REFLECT/TUTOR the same way, without special cases.
 *
 * Before Task 5 (structured-plan-engine-907066) [CompletedTurn] also carried `planAwaitingApproval` — it
 * has been removed since (see the TurnCompletion.kt kdoc): whether a plan is shown and awaits a decision
 * is now PlanRepository state, not a derivative of one turn.
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
    // The canRetry decision lives in TurnManager (blankError != null && there is user text),
    // covered by TurnManagerTest; here only the pure policy: an empty answer → a blank error.
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
  fun `a model that wrapped up at the limit gets no canned note on top`() {
    val out = resolveCompletedTurn(
      result("Done: the notes are found. Not done: the summary. Ask me to finish it.")
        .copy(hitIterationLimit = true, wrappedUp = true, iterations = 10),
    )
    assertEquals("Done: the notes are found. Not done: the summary. Ask me to finish it.", out.responseText)
  }

  @Test
  fun `лимит шагов без текста — не «пустой ответ», а пометка о лимите`() {
    // Before: the last round is only a tool_call, there are no preambles, and the user saw an "empty answer".
    val out = resolveCompletedTurn(result("").copy(hitIterationLimit = true, iterations = 10))
    assertEquals(TurnTexts.ITERATION_LIMIT_NOTE, out.responseText)
    assertNull(out.blankError)
  }

  // --- the builder chat's "Review your agent" card comes from agent_draft, not from render_card ---

  private fun draftCall(data: String) =
    ToolTrace(iteration = 1, toolName = "agent_draft", arguments = emptyMap(), result = ToolResult.Success(data))

  private val reviewCard =
    """{"template":"agent_review","params":{"agent_id":"my-agent-1","name":"N"},"status":"ready"}"""

  @Test
  fun `a successful agent_draft finish becomes the review card of the turn`() {
    val out = resolveCompletedTurn(result("Your draft is ready.", listOf(draftCall(reviewCard))))

    assertEquals(listOf(reviewCard), out.cardSpecs)
  }

  @Test
  fun `other agent_draft results are data for the model, never a message`() {
    val update = draftCall("""{"agent_id":"my-agent-1","draft":{"name":"N"},"problems":[]}""")

    val out = resolveCompletedTurn(result("Next question.", listOf(update)))

    assertEquals(emptyList<String>(), out.cardSpecs)
  }

  @Test
  fun `a failed finish shows no card`() {
    val failed = ToolTrace(iteration = 1, toolName = "agent_draft", arguments = emptyMap(), result = ToolResult.Error("not ready"))

    assertEquals(emptyList<String>(), resolveCompletedTurn(result("What should it be called?", listOf(failed))).cardSpecs)
  }
}
