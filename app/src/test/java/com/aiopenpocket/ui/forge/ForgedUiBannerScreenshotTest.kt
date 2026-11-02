package com.aiopenpocket.ui.forge

// @trace cloud-forged-screens-40d702

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.forge.ForgedAgentUi
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's line about the agent's own interface ([ForgedUiBannerContent], the pure part of
 * `ForgedUiBanner`): two baselines (a job is being built, the screen is ready) and the rules for when
 * the banner shows at all. Each baseline test asserts a tag that only its state renders: the progress
 * state has no "Open" button, the ready state has one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ForgedUiBannerScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun backToDefault() {
    AppLanguage.code = AppLanguage.RU
  }

  private val ready = ForgedScreenState.Ready("job-ban-3", "a water tracker", "WaterScreen", 2, "job-ban-3.ktx")

  @Composable
  private fun Frame(ui: ForgedAgentUi, onOpenUi: () -> Unit = {}) {
    MyApplicationTheme(mode = ThemeMode.DARK) {
      Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ForgedUiBannerContent(ui = ui, onOpenUi = onOpenUi)
      }
    }
  }

  @Test
  fun forged_banner_building() {
    composeTestRule.setContent {
      Frame(ForgedAgentUi(state = ForgedScreenState.Working("job-ban-1", "a water tracker", "compiling"), loaded = true))
    }
    composeTestRule.onNodeWithTag("forged_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_banner_open").assertDoesNotExist()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_banner_building.png")
  }

  @Test
  fun forged_banner_ready() {
    composeTestRule.setContent {
      Frame(ForgedAgentUi(state = ready, bundle = byteArrayOf(1, 2, 3), customUiEnabled = true, loaded = true))
    }
    composeTestRule.onNodeWithTag("forged_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_banner_open").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_banner_ready.png")
  }

  @Test
  fun `a job in flight shows the banner even before the person switched the interface on`() {
    composeTestRule.setContent {
      Frame(ForgedAgentUi(state = ForgedScreenState.Requested("job-ban-2", "req"), customUiEnabled = false, loaded = true))
    }
    composeTestRule.onNodeWithTag("forged_banner").assertIsDisplayed()
  }

  @Test
  fun `the open button hands control to the caller`() {
    var opened = 0
    composeTestRule.setContent {
      Frame(ForgedAgentUi(state = ready, bundle = byteArrayOf(1), customUiEnabled = true, loaded = true), onOpenUi = { opened++ })
    }

    composeTestRule.onNodeWithTag("forged_banner_open").performClick()

    assertEquals(1, opened)
  }

  @Test
  fun `a ready screen the person did not switch on shows no banner`() {
    composeTestRule.setContent {
      // Ready on disk, but the agent stays in the chat.
      Frame(ForgedAgentUi(state = ready, bundle = byteArrayOf(1), customUiEnabled = false, loaded = true))
    }
    composeTestRule.onNodeWithTag("forged_banner").assertDoesNotExist()
  }

  @Test
  fun `a switched-on screen without its bundle on disk shows no banner`() {
    composeTestRule.setContent {
      Frame(ForgedAgentUi(state = ready, bundle = null, customUiEnabled = true, loaded = true))
    }
    composeTestRule.onNodeWithTag("forged_banner").assertDoesNotExist()
  }

  @Test
  fun `nothing built, a question, or a failure show no banner`() {
    var ui by mutableStateOf(ForgedAgentUi(state = ready, bundle = byteArrayOf(1), customUiEnabled = true, loaded = true))
    composeTestRule.setContent { Frame(ui) }
    // The control: the same setup with a ready state does show the banner.
    composeTestRule.onNodeWithTag("forged_banner").assertIsDisplayed()

    for (state in listOf(
      ForgedScreenState.None,
      ForgedScreenState.NeedsInfo("job-ban-4", "req", "q?"),
      ForgedScreenState.Failed("job-ban-5", "req", "boom"),
    )) {
      composeTestRule.runOnIdle { ui = ForgedAgentUi(state = state, bundle = byteArrayOf(1), customUiEnabled = true, loaded = true) }
      composeTestRule.onNodeWithTag("forged_banner").assertDoesNotExist()
    }
  }
}
