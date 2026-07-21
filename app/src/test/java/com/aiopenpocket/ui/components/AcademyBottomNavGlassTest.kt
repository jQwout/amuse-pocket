package com.aiopenpocket.ui.components

// @trace glass-design-system-21a3fe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aiopenpocket.ui.components.glass.auroraBackground
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

/**
 * Скриншот-эталон стеклянной нижней навигации: [AcademyBottomNav] поверх ауроры
 * ([Modifier.auroraBackground]), чтобы у стекла было что тонировать/размывать.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AcademyBottomNavGlassTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun bottom_nav_over_aurora() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          AcademyBottomNav(
            selectedTab = AcademyTab.Chat,
            onTabSelected = {},
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/bottomnav_glass.png")
  }
}
