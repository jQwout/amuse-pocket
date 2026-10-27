package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the chat preview frame ([ChatPreviewScaffold]) to PNG (Roborazzi) — so that "what the chat
 * looks like" can be seen without launching the app and pinned in screenshot tests. To record baselines:
 * `gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`; to compare — an ordinary test run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChatScreenScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun chat_conversation_screenshot() {
    composeTestRule.setContent {
      ChatPreviewScaffold(
        title = "Чат",
        messages = previewChatMessages,
        stats = ResponseStats(responseTimeMs = 1840, completionTokens = 128, contextTokensUsed = 3200),
      )
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_conversation.png")
  }

  @Test
  fun chat_lesson_cards_screenshot() {
    composeTestRule.setContent {
      ChatPreviewScaffold(
        title = "Как ИИ читает твой запрос",
        interactiveCards = true,
        showInput = false,
        messages = previewLessonMessages,
      )
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_lesson_cards.png")
  }

  @Test
  fun chat_streaming_screenshot() {
    composeTestRule.setContent {
      ChatPreviewScaffold(
        title = "Чат",
        messages = listOf(previewMessage("user", "Расскажи про токены простыми словами.")),
        streamingText = "Токен — это кусочек текста: слово или часть слова. Модель читает и пишет",
      )
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_streaming.png")
  }

  /** The real [ChatInput] (not a stub) in three states: empty / with text / an answer in progress. */
  @Test
  fun chat_input_states_screenshot() {
    composeTestRule.setContent {
      ChatInputStatesPreview()
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_input_states.png")
  }
}
