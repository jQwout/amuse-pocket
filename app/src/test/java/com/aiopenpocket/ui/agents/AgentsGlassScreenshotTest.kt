package com.aiopenpocket.ui.agents

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshot baseline of the Tools/Agents restyle: the aurora background, a transparent
 * [AcademyCollapsingScaffold] over it, [GlassCard] on the agent cards. [AgentsScreen] pulls
 * [AgentsViewModel] itself through metroViewModel(), which has no DI graph to take in Robolectric, so we
 * render [AgentsPreviewScaffold] — the same visual frame on the presets of
 * [com.aiopenpocket.agent.core.agent.MiniAgentCatalog], adapted into `AgentDefinition` (see the
 * HomePreviewScaffold pattern in HomeScreen.kt).
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

  /**
   * The collapsed state is proof of what the baseline above CANNOT show. The glass of the top island
   * appears by `collapsedFraction`: at the top of the list (the baseline above) it is absent on purpose —
   * the content has not slid under the bar yet, and the large title lies right on the canvas. This shot is
   * taken AFTER scrolling: the bar is collapsed, the list runs under it, and the glass is in place and keeps
   * things readable.
   */
  @Test
  fun agents_glass_collapsed_screenshot() {
    composeTestRule.setContent {
      AgentsPreviewScaffold()
    }
    composeTestRule.onRoot().performTouchInput { swipeUp() }
    composeTestRule.waitForIdle()
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agents_glass_collapsed.png")
  }
}
