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
 * Скриншот-эталон glass-рестайла чата (Task 4, Plan B): аврора-фон, [GlassSurface] на неподвижной
 * обвязке (топбар + инпут-пилюля), [glassSource] на ленте сообщений. Пузыри ([MessageBubble],
 * [StreamingBubble]) — БЕЗ изменений (сохранён цвет user/assistant, никакого blur внутри LazyColumn
 * items — перф-критичный экран). Рендерим тот же [ChatPreviewScaffold], что и
 * [ChatScreenScreenshotTest], — теперь он несёт актуальный glass-каркас.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChatGlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun chat_glass_screenshot() {
    composeTestRule.setContent {
      ChatPreviewScaffold(
        title = "Чат",
        messages = previewChatMessages,
        stats = ResponseStats(responseTimeMs = 1840, completionTokens = 128, contextTokensUsed = 3200),
      )
    }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_glass.png")
  }
}
