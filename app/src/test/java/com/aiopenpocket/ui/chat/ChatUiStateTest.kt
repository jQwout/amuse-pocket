package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.identity.ChatFontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {

  /**
   * Regression: changing the chat font size "did not work", because [ChatUiState.resetFor] (called on every
   * entry into a conversation) recreated the state with defaults, wiping the pref-derived fields. They are
   * set by a reactive collector from DataStore, which does not re-emit an unchanged value — so resetFor
   * must KEEP them.
   */
  @Test
  fun resetForPreservesSettingsDerivedFields() {
    val prev = ChatUiState(
      conversationId = "old",
      chatFontScale = ChatFontScale.LARGE,
      goalMaxIterations = 7,
    )

    val reset = ChatUiState.resetFor("new", prev.chatFontScale, prev.goalMaxIterations)

    assertEquals("new", reset.conversationId)
    assertEquals(ChatFontScale.LARGE, reset.chatFontScale) // not reset to MEDIUM
    assertEquals(7, reset.goalMaxIterations)               // not reset to 3
  }

  /** The conversation state really is cleared at the same time (we do not carry over anything extra). */
  @Test
  fun resetForClearsConversationState() {
    val prev = ChatUiState(
      conversationId = "old",
      lessonStepIndex = 5,
      chatFontScale = ChatFontScale.LARGE,
      goalMaxIterations = 7,
    )

    val reset = ChatUiState.resetFor("new", prev.chatFontScale, prev.goalMaxIterations)

    assertEquals(0, reset.lessonStepIndex)
  }

  /**
   * F5: on the "segment" step without a Meta key a red "This step needs a Meta Model API key" is set as the
   * chat's error. It sat under the privacy paragraph, the quiz and the "Lesson complete" card because nothing
   * cleared it when the learner moved on. Moving the lesson cursor must take the step's failure with it.
   */
  @Test
  fun lessonCursorMoveClearsTheErrorOfTheStepItLeft() {
    val onSegmentStep = ChatUiState(
      lessonMode = true,
      lessonStepIndex = 14,
      error = "This step needs a Meta Model API key",
      canRetry = true,
    )

    val next = onSegmentStep.withLessonCursor(stepIndex = 15)

    assertEquals(15, next.lessonStepIndex)
    assertNull(next.error)
    assertFalse(next.canRetry)
    assertFalse(next.lessonFinished)
  }

  @Test
  fun finishingTheLessonClearsTheErrorToo() {
    val last = ChatUiState(lessonMode = true, lessonStepIndex = 20, error = "old problem")

    val done = last.withLessonCursor(finished = true)

    assertTrue(done.lessonFinished)
    assertEquals(20, done.lessonStepIndex) // the index stays: only the completion card is added
    assertNull(done.error)
  }
}
