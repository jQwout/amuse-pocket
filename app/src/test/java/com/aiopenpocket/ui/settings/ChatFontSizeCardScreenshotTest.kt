package com.aiopenpocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChatFontSizeCardScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test fun chat_font_size_card_dark() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        androidx.compose.foundation.layout.Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 24.dp),
        ) {
          ChatFontSizeCard(
            selected = ChatFontScale.LARGE,
            onSelect = {},
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/settings_chat_font_size.png")
  }
}
