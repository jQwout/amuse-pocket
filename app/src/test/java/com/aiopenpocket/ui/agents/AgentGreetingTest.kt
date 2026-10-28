package com.aiopenpocket.ui.agents

// @trace user-agent-builder-848153
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The first message of an agent's chat. On a device (orchestrator/evidence/M3) it read "…drafts emails
 * about my meetings How can I help?": a description is free text, and its author is under no obligation
 * to end it with a full stop.
 */
class AgentGreetingTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  @Test
  fun `a description without a full stop gets one`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(
      "This is the “Secretary” agent. Drafts emails about my meetings. How can I help?",
      agentGreeting("Secretary", "Drafts emails about my meetings"),
    )
  }

  @Test
  fun `a description that already ends in a full stop does not get a second one`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(
      "This is the “Secretary” agent. Drafts emails. How can I help?",
      agentGreeting("Secretary", "Drafts emails."),
    )
  }

  /** A question mark or an ellipsis is an end of sentence too — "Helps you?." is not. */
  @Test
  fun `other sentence endings are left alone`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(
      "This is the “Coach” agent. Tired of excuses? How can I help?",
      agentGreeting("Coach", "Tired of excuses?"),
    )
  }

  /** An empty description used to leave a double space in the middle of the line. */
  @Test
  fun `an empty description leaves no gap`() {
    AppLanguage.code = AppLanguage.EN
    val greeting = agentGreeting("Secretary", "   ")
    assertEquals("This is the “Secretary” agent. How can I help?", greeting)
    assertFalse("a double space in the greeting: $greeting", greeting.contains("  "))
  }

  @Test
  fun `the greeting is Russian in the Russian interface`() {
    AppLanguage.code = AppLanguage.RU
    assertEquals(
      "Это агент «Секретарь». Пишет письма про мои встречи. Чем помочь?",
      agentGreeting("Секретарь", "Пишет письма про мои встречи"),
    )
  }
}
