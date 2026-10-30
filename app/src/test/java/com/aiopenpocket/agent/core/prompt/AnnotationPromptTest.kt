package com.aiopenpocket.agent.core.prompt

// @trace multi-chat-conversations-b3c93b

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-list title must name the topic, not the outcome. A draft waiting for approval came back as
 * "Declined emails to Masha"; the cure lives in the prompt, so the test pins what the prompt says.
 */
class AnnotationPromptTest {

  @Test
  fun `role and tone are stated up front in the system message`() {
    assertTrue(AnnotationPrompt.SYSTEM.contains("нейтральным названием темы"))
    assertTrue(AnnotationPrompt.SYSTEM.contains("только JSON"))
  }

  @Test
  fun `title rule asks for a neutral topic and forbids outcomes and verdicts`() {
    for (english in listOf(true, false)) {
      val prompt = AnnotationPrompt.user("user: draft an email to Masha", english)
      val titleRule = prompt.lines().first { it.startsWith("- title:") }
      assertTrue(titleRule.contains("нейтральное название темы или задачи"))
      assertTrue(titleRule.contains(AnnotationPrompt.TITLE_TONE))
      assertTrue(titleRule.contains("без оценок и вердиктов"))
      assertTrue(titleRule.contains("ждёт подтверждения"))
    }
  }

  @Test
  fun `a good and a bad example sit next to the rule in the interface language`() {
    val en = AnnotationPrompt.user("t", english = true)
    assertTrue(en.contains("«Email to Masha»"))
    assertTrue(en.contains("«Declined emails to Masha»"))
    assertTrue(en.contains("- title: нейтральное название темы или задачи, до 6 слов, in English."))
    val ru = AnnotationPrompt.user("t", english = false)
    assertTrue(ru.contains("«Письмо Маше»"))
    assertTrue(ru.contains("- title: нейтральное название темы или задачи, до 6 слов, на русском."))
  }

  @Test
  fun `the transcript and the json shape are still in the prompt`() {
    val prompt = AnnotationPrompt.user("user: hello", english = true)
    assertTrue(prompt.contains("user: hello"))
    assertTrue(prompt.contains("\"title\": \"...\", \"summary\": \"...\", \"tags\""))
    assertEquals(1, prompt.lines().count { it.startsWith("- summary:") })
  }
}
