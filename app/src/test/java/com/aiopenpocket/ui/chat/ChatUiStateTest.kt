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
   * F5: on the "segment" step without a Meta key a red "This step needs a Meta Model API key" stayed under the
   * privacy paragraph, the quiz and the "Lesson complete" card, because nothing took it away when the learner
   * moved on. A step's problem has its own slot and the lesson cursor takes it along.
   */
  @Test
  fun lessonCursorMoveClearsTheProblemOfTheStepItLeft() {
    val onSegmentStep = ChatUiState(
      lessonMode = true,
      lessonStepIndex = 14,
      lessonStepProblem = "This step needs a Meta Model API key",
    )

    val next = onSegmentStep.withLessonCursor(stepIndex = 15)

    assertEquals(15, next.lessonStepIndex)
    assertNull(next.lessonStepProblem)
    assertFalse(next.lessonFinished)
  }

  /**
   * A question asked mid-lesson whose turn failed is still unanswered after "Next", and "Retry" is the only way
   * back to it. The turn's error is not the step's to clear: the turn session re-derives it on every change of the
   * message list, so a clear here would be undone a moment later or not, depending on delivery order.
   */
  @Test
  fun lessonCursorMoveLeavesTheFailedTurnAndItsRetryAlone() {
    val state = ChatUiState(
      lessonMode = true,
      lessonStepIndex = 3,
      error = "The model did not answer",
      canRetry = true,
      lessonStepProblem = "This step needs a Meta Model API key",
    )

    val next = state.withLessonCursor(stepIndex = 4)

    assertEquals("The model did not answer", next.error)
    assertTrue(next.canRetry)
    assertNull(next.lessonStepProblem)
  }

  @Test
  fun finishingTheLessonClearsTheStepProblemAndKeepsTheTurnError() {
    val last = ChatUiState(
      lessonMode = true,
      lessonStepIndex = 20,
      error = "turn failed",
      canRetry = true,
      lessonStepProblem = "old step problem",
    )

    val done = last.withLessonCursor(finished = true)

    assertTrue(done.lessonFinished)
    assertEquals(20, done.lessonStepIndex) // the index stays: only the completion card is added
    assertNull(done.lessonStepProblem)
    assertEquals("turn failed", done.error)
    assertTrue(done.canRetry)
  }
}
