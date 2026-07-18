package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.identity.ChatFontScale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUiStateTest {

  /**
   * Регрессия: смена размера шрифта чата «не работала», потому что [ChatUiState.resetFor]
   * (вызывается при каждом входе в диалог) пересоздавал состояние с дефолтами, затирая
   * pref-производные поля. Их кладёт реактивный коллектор из DataStore, а он на неизменном
   * значении не переэмитит — значит resetFor обязан их СОХРАНЯТЬ.
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
    assertEquals(ChatFontScale.LARGE, reset.chatFontScale) // не сброшено в MEDIUM
    assertEquals(7, reset.goalMaxIterations)               // не сброшено в 3
  }

  /** Разговорное состояние при этом действительно чистится (не переносим лишнего). */
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
