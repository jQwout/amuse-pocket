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
 * The screenshot baseline of the chat's glass restyle (Task 4, Plan B): the aurora background,
 * [GlassSurface] on the fixed chrome (top bar + input pill), [glassSource] on the message feed. The bubbles
 * ([MessageBubble], [StreamingBubble]) are UNCHANGED (the user/assistant colors are kept, no blur inside
 * LazyColumn items — a performance-critical screen). We render the same [ChatPreviewScaffold] as
 * [ChatScreenScreenshotTest] — it now carries the current glass frame.
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
