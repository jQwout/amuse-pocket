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
 * Скриншот-эталон рестайла Home: аврора-фон, [GlassCard] на карточках, стеклянная нижняя нав
 * ([AcademyBottomNav] с подключённым [glassState]). [HomeScreen] сам тянет обе ViewModel через
 * metroViewModel(), которому в Robolectric неоткуда взять DI-граф, поэтому рендерим
 * [HomePreviewScaffold] — тот же визуальный каркас на фейковых данных (см. паттерн
 * ChatPreviewScaffold в ChatScreen.kt).
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
