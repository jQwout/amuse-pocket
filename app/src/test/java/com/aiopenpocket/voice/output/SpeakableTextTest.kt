package com.aiopenpocket.voice.output

// @trace voice-assistant-mode-a81a99

import com.aiopenpocket.agent.core.prompt.REFLECTION_FINAL_HEADING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextTest {

  @Test
  fun `markup is dropped and the words stay`() {
    val reply = """
      ## Plan for **today**
      - Buy _milk_
      1. Call [Anna](https://example.com/anna)
      > Note: `rain` later
    """.trimIndent()

    assertEquals("Plan for today\nBuy milk\nCall Anna\nNote: rain later", speakableText(reply))
  }

  @Test
  fun `code blocks, images and bare links are not read`() {
    val reply = "Run this:\n```kotlin\nprintln(1)\n```\n![chart](x.png)\nSee https://example.com/page now"

    assertEquals("Run this:\nSee now", speakableText(reply))
  }

  @Test
  fun `a table reads as its cells`() {
    val reply = "| Day | Steps |\n|---|---|\n| Mon | 4000 |"

    assertEquals("Day , Steps\nMon , 4000", speakableText(reply))
  }

  @Test
  fun `words with underscores and snake case are kept`() {
    assertEquals("call web_search first", speakableText("call web_search first"))
  }

  @Test
  fun `a reflection reply reads only its result`() {
    val reply = "Draft text\n\n**Critique** too long" + REFLECTION_FINAL_HEADING + "The short answer."

    assertEquals("The short answer.", speakableText(reply))
  }

  @Test
  fun `chunks stay under the limit and cut at a sentence`() {
    val text = "First sentence here. Second sentence here. Third one."

    val chunks = speechChunks(text, maxLength = 25)

    assertEquals(listOf("First sentence here.", "Second sentence here.", "Third one."), chunks)
    assertTrue(chunks.all { it.length <= 25 })
  }

  @Test
  fun `a word longer than the limit is still split`() {
    val chunks = speechChunks("a".repeat(30), maxLength = 10)

    assertEquals(listOf("a".repeat(10), "a".repeat(10), "a".repeat(10)), chunks)
    assertFalse(chunks.any { it.isEmpty() })
  }
}
