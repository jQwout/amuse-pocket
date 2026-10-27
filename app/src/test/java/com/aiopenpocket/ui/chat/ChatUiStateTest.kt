package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.identity.ChatFontScale
import org.junit.Assert.assertEquals
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
}
