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
}
