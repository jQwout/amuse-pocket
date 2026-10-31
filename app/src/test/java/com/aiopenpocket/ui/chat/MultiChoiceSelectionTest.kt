package com.aiopenpocket.ui.chat

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the `multi_choice` card without a screen: ticks, the "Next" gate, the answer text, the saved state. */
class MultiChoiceSelectionTest {

  @Test
  fun `a tap ticks and a second tap unticks`() {
    val ticked = MultiChoiceSelection.toggle(emptySet(), 1, max = 3)
    assertEquals(setOf(1), ticked)
    assertEquals(emptySet<Int>(), MultiChoiceSelection.toggle(ticked, 1, max = 3))
  }

  @Test
  fun `ticks stop at max and the tick the person already made is kept`() {
    val atLimit = setOf(0, 2)
    assertEquals(atLimit, MultiChoiceSelection.toggle(atLimit, 3, max = 2))
  }

  @Test
  fun `with max one a new tap replaces the tick like a radio button`() {
    assertEquals(setOf(2), MultiChoiceSelection.toggle(setOf(0), 2, max = 1))
  }

  @Test
  fun `next needs at least min and at most max ticks`() {
    assertFalse(MultiChoiceSelection.canSubmit(emptySet(), min = 1, max = 3))
    assertTrue(MultiChoiceSelection.canSubmit(setOf(0), min = 1, max = 3))
    assertTrue(MultiChoiceSelection.canSubmit(setOf(0, 1, 2), min = 1, max = 3))
    assertFalse(MultiChoiceSelection.canSubmit(setOf(0), min = 2, max = 3))
    assertTrue(MultiChoiceSelection.canSubmit(emptySet(), min = 0, max = 3))
  }

  @Test
  fun `the answer is the ticked labels in card order joined with commas`() {
    val options = listOf("Memory", "Reminders", "Calendar")
    // Tapped in the order 2, 0 - the text still follows the card, so the same choice always reads the same.
    assertEquals("Memory, Calendar", MultiChoiceSelection.answerText(options, setOf(2, 0)))
  }

  @Test
  fun `an empty choice still says something`() {
    assertTrue(MultiChoiceSelection.answerText(listOf("a", "b"), emptySet()).isNotBlank())
  }

  @Test
  fun `a submitted card survives a round trip through its saved state`() {
    val state = MultiChoiceSelection.encodeSubmitted(setOf(2, 0))
    assertEquals(setOf(0, 2), MultiChoiceSelection.decodeSubmitted(state, optionCount = 3))
  }

  @Test
  fun `an unanswered or foreign state means not submitted`() {
    assertNull(MultiChoiceSelection.decodeSubmitted(null, 3))
    assertNull(MultiChoiceSelection.decodeSubmitted("0:1,1:2", 3)) // a lesson quiz's state
  }

  @Test
  fun `a stale index past the options is dropped`() {
    assertEquals(setOf(1), MultiChoiceSelection.decodeSubmitted("done:1,7", optionCount = 3))
  }
}
