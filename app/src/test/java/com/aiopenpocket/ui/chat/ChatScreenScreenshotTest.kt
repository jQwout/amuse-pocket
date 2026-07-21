package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import androidx.compose.ui.test.junit4.createComposeRule
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
 * Рендерит превью-каркас чата ([ChatPreviewScaffold]) в PNG (Roborazzi) — чтобы «как выглядит чат»
 * можно было увидеть без запуска приложения и зафиксировать в скриншот-тестах. Записать эталоны:
 * `gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`; сверять — обычным прогоном теста.
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

  /** Настоящий [ChatInput] (не заглушка) в трёх состояниях: пусто / с текстом / идёт ответ. */
  @Test
  fun chat_input_states_screenshot() {
    composeTestRule.setContent {
      ChatInputStatesPreview()
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_input_states.png")
  }
}
