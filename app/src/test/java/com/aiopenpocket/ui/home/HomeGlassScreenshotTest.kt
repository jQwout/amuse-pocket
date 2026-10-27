package com.aiopenpocket.ui.home

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
 * The screenshot baseline of the Home restyle: the aurora background, [GlassCard] on the cards, the glass
 * bottom nav ([AcademyBottomNav] with [glassState] connected). [HomeScreen] pulls both ViewModels itself
 * through metroViewModel(), which has no DI graph to take in Robolectric, so we render
 * [HomePreviewScaffold] — the same visual frame on fake data (see the ChatPreviewScaffold pattern in
 * ChatScreen.kt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class HomeGlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun home_glass_screenshot() {
    composeTestRule.setContent {
      HomePreviewScaffold()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/home_glass.png")
  }

  @Test
  fun home_glass_empty_state_screenshot() {
    composeTestRule.setContent {
      HomePreviewScaffold(recentChats = emptyList())
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/home_glass_empty.png")
  }
}
