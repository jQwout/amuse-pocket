package com.aiopenpocket.agent.core.prompt

import com.aiopenpocket.agent.core.memory.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowBuilderTest {

  private var counter = 0

  private fun msg(
    role: String,
    content: String,
    toolCallsJson: String? = null,
  ): Message = Message(
    id = "m${counter++}",
    conversationId = "c1",
    role = role,
    content = content,
    toolCallsJson = toolCallsJson,
    cardSpec = null,
    createdAt = counter.toLong(),
  )

  @Test
  fun `empty input yields empty window`() {
    val window = ContextWindowBuilder.build(emptyList())
    assertTrue(window.verbatim.isEmpty())
    assertFalse(window.hasMiddleGap)
    assertEquals(0, window.middleMessageCount)
  }

  @Test
  fun `short dialog kept verbatim without gap`() {
    val messages = (1..4).map { msg("user", "m$it") }
    val window = ContextWindowBuilder.build(messages, headCount = 2, tailCount = 2)
    assertEquals(4, window.verbatim.size)
    assertFalse(window.hasMiddleGap)
  }

  @Test
  fun `long dialog produces middle gap with head and tail`() {
    val messages = (1..10).map { msg("user", "m$it") }
    val window = ContextWindowBuilder.build(messages, headCount = 2, tailCount = 2)
    // head(2) + tail(2) kept verbatim; middle of 6 collapses into a gap.
    assertEquals(4, window.verbatim.size)
    assertTrue(window.hasMiddleGap)
    assertEquals(6, window.middleMessageCount)
    assertEquals("m1", window.verbatim.first().content)
    assertEquals("m10", window.verbatim.last().content)
  }

  @Test
  fun `blank content messages without tool calls are filtered out`() {
    // Regression guard for inline render_card: card-only messages have blank
    // content and must never reach the LLM as empty assistant turns.
    val messages = listOf(
      msg("user", "hi"),
      msg("assistant", ""), // card-only message
      msg("user", "bye"),
    )
    val window = ContextWindowBuilder.build(messages, headCount = 8, tailCount = 40)
    assertEquals(2, window.verbatim.size)
    assertTrue(window.verbatim.none { it.content.isBlank() })
  }

  @Test
  fun `blank content message with tool calls is preserved`() {
    val messages = listOf(
      msg("user", "do it"),
      msg("assistant", "", toolCallsJson = "[{\"id\":\"1\"}]"),
    )
    val window = ContextWindowBuilder.build(messages, headCount = 8, tailCount = 40)
    assertEquals(2, window.verbatim.size)
  }

  // --- бюджет токенов -----------------------------------------------------------------------------

  /** 300 символов ≈ 100 токенов по LlmContextConfig.estimateTokens (chars / 3). */
  private fun bulky(i: Int) = PrePromptAssembler.PromptMessage("user", "m$i:" + "x".repeat(300 - "m$i:".length), null)

  private fun ids(messages: List<PrePromptAssembler.PromptMessage>) = messages.map { it.content.substringBefore(':') }

  @Test
  fun `token budget applies even when the dialog fits into head plus tail`() {
    // Раньше диалог ≤ head+tail (48 реплик) уходил целиком без проверки бюджета.
    val messages = (1..10).map { msg("user", "m$it:" + "x".repeat(300)) }

    val window = ContextWindowBuilder.buildFromParts(
      head = messages, tail = emptyList(), totalPromptable = 10,
      headCount = 8, tailCount = 40, budgetTokens = 500,
    )

    assertTrue("бюджет не применён: ${window.verbatim.size} реплик", window.verbatim.size < 10)
    assertTrue("m10:" in window.verbatim.last().content)
    assertTrue("выброшенные бюджетом реплики должны считаться пропуском", window.hasMiddleGap)
  }

  @Test
  fun `the current message is never dropped, even when it alone exceeds the budget`() {
    val messages = listOf(bulky(1), bulky(2), PrePromptAssembler.PromptMessage("user", "last:" + "x".repeat(3_000), null))

    val trimmed = ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 50, headCount = 8)

    assertEquals(listOf("last"), ids(trimmed))
  }

  @Test
  fun `head plus the current message - the current message survives`() {
    // Ровно сценарий старого бага: голова из 8 реплик + текущий вопрос, всё сверх бюджета. Старый
    // алгоритм выбрасывал именно текущий вопрос.
    val messages = (1..9).map { bulky(it) }

    val trimmed = ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 450, headCount = 8)

    assertEquals("m9", ids(trimmed).last())
  }

  @Test
  fun `older tail goes first, then head, and only then the most recent messages`() {
    // 12 реплик по ~100 токенов: голова 0..1, старый хвост 2..5, защищённые недавние 6..11.
    val messages = (0..11).map { bulky(it) }

    // Нужно выбросить 4 — уходит старый хвост, голова и недавние целы.
    assertEquals(
      listOf("m0", "m1", "m6", "m7", "m8", "m9", "m10", "m11"),
      ids(ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 800, headCount = 2, protectedRecent = 6)),
    )
    // Нужно выбросить 6 — голова уходит раньше недавних.
    assertEquals(
      listOf("m6", "m7", "m8", "m9", "m10", "m11"),
      ids(ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 600, headCount = 2, protectedRecent = 6)),
    )
    // Дальше — недавние от старых к новым; последняя остаётся.
    assertEquals(
      listOf("m9", "m10", "m11"),
      ids(ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 300, headCount = 2, protectedRecent = 6)),
    )
  }

  @Test
  fun `within budget nothing is dropped`() {
    val messages = (0..5).map { bulky(it) }

    assertEquals(messages, ContextWindowBuilder.trimVerbatimToTokenBudget(messages, budgetTokens = 10_000))
  }
}
