package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reminder push's own text. In the shade (orchestrator/evidence/M3) the heading and the body were
 * both "Call mom": the heading was the reminder's title and the body its message, and for a one-line
 * reminder the model writes the same sentence into both.
 */
class ReminderPushBodyTest {

  @Test
  fun `the same text in both fields is said once`() {
    assertEquals("Call mom", reminderPushBody("Call mom", "Call mom"))
  }

  @Test
  fun `without a message the title is the text`() {
    assertEquals("Call mom", reminderPushBody("Call mom", null))
    assertEquals("Call mom", reminderPushBody("Call mom", "   "))
  }

  /** The message is usually the title plus the detail: prefixing the title again would stutter. */
  @Test
  fun `a message that already contains the title is shown as it is`() {
    assertEquals("Call mom at 7pm", reminderPushBody("Call mom", "Call mom at 7pm"))
    assertEquals("call mom at 7pm", reminderPushBody("Call Mom", "call mom at 7pm"))
  }

  /** Neither part is thrown away when they say different things. */
  @Test
  fun `a title and an unrelated message are both kept`() {
    assertEquals("Вода — Пора выпить воды", reminderPushBody("Вода", "Пора выпить воды"))
  }

  @Test
  fun `surrounding whitespace never reaches the shade`() {
    assertEquals("Call mom", reminderPushBody("  Call mom  ", " Call mom "))
  }
}
