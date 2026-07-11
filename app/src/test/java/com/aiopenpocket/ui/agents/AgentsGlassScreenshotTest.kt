package com.aiopenpocket.ui.agents

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
 * Скриншот-эталон рестайла Tools/Agents: аврора-фон, прозрачный [AcademyCollapsingScaffold]
 * поверх неё, [GlassCard] на карточках агентов. [AgentsScreen] сам тянет [AgentsViewModel] через
 * metroViewModel(), которому в Robolectric неоткуда взять DI-граф, поэтому рендерим
 * [AgentsPreviewScaffold] — тот же визуальный каркас на реальных [MiniAgent] из
 * [com.aiopenpocket.agent.core.agent.MiniAgentCatalog] (см. паттерн HomePreviewScaffold в
 * HomeScreen.kt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentsGlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun agents_glass_screenshot() {
    composeTestRule.setContent {
      AgentsPreviewScaffold()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agents_glass.png")
  }
}
