package com.aiopenpocket.ui.agents.detail

// @trace cloud-forged-screens-40d702

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.forge.ForgedAgentUi
import com.aiopenpocket.agent.core.forge.ForgedScreenState
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
 * One baseline per state of the cloud-forged-screen section (`ForgedScreenSection`), rendered through
 * [ForgedScreenSectionPreviewFrame]: the REAL section with fixed state and no ViewModel. The
 * section's header always carries the Chat / Own interface chips; the card under them depends on the
 * state. The preview frame switches the own interface on for every state but None, so the two None
 * baselines that need "switched on" use [OwnInterfaceFrame], a thin frame of this test around the same
 * real section.
 *
 * A ready bundle is drawn by a STAND-IN card, not by Ketoy: the interpreter needs a real runtime and VM
 * that Robolectric cannot run, so the `ready` baseline guards the frame around the screen (header with
 * the version, the 320dp slot, the open / update / regenerate controls), never the guest screen's pixels.
 * Each test also asserts a tag that only the state under test renders, so a baseline recorded from the
 * wrong state cannot pass quietly.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ForgedScreenSectionScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** The agent lives in the chat: the chips and the explanation, no build button yet. */
  @Test
  fun forged_section_none() {
    composeTestRule.setContent {
      ForgedScreenSectionPreviewFrame(state = ForgedScreenState.None, draft = "A water tracker with 8 glasses and a reset button")
    }
    composeTestRule.onNodeWithTag("forged_mode_chat").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_mode_custom").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_build").assertDoesNotExist()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_none.png")
  }

  /** The own interface is switched on and nothing was built yet: "Build the screen" is offered. */
  @Test
  fun forged_section_none_own_interface() {
    composeTestRule.setContent { OwnInterfaceFrame(configured = true) }
    composeTestRule.onNodeWithTag("forged_build").assertIsEnabled()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_none_own_interface.png")
  }

  /** No gateway address/token in Settings: the hint shows and the build button is off. */
  @Test
  fun forged_section_none_not_configured() {
    composeTestRule.setContent { OwnInterfaceFrame(configured = false) }
    composeTestRule.onNodeWithTag("forged_not_configured").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_build").assertIsNotEnabled()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_none_not_configured.png")
  }

  @Test
  fun forged_section_working() {
    composeTestRule.setContent {
      ForgedScreenSectionPreviewFrame(
        state = ForgedScreenState.Working("job-shot-1", "A water tracker with 8 glasses", "Compiling the screen"),
      )
    }
    composeTestRule.onNodeWithTag("forged_status").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_working.png")
  }

  @Test
  fun forged_section_needs_info() {
    composeTestRule.setContent {
      ForgedScreenSectionPreviewFrame(
        state = ForgedScreenState.NeedsInfo("job-shot-2", "A budget donut", "Which currency should the donut show?"),
      )
    }
    composeTestRule.onNodeWithTag("forged_question").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_needs_info.png")
  }

  @Test
  fun forged_section_ready() {
    composeTestRule.setContent {
      ForgedScreenSectionPreviewFrame(
        state = ForgedScreenState.Ready(
          jobId = "job-shot-3",
          request = "A water tracker with 8 glasses",
          entryPoint = "WaterScreen",
          version = 2,
          fileName = "job-shot-3.ktx",
        ),
      )
    }
    composeTestRule.onNodeWithTag("forged_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("forged_open").assertIsDisplayed()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_ready.png")
  }

  @Test
  fun forged_section_failed() {
    composeTestRule.setContent {
      ForgedScreenSectionPreviewFrame(
        state = ForgedScreenState.Failed("job-shot-4", "A water tracker", "The forge could not make this screen."),
      )
    }
    composeTestRule.onNodeWithTag("forged_build").assertIsEnabled()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forged_section_failed.png")
  }

  /** The real section with the own interface switched on and no screen yet. */
  @Composable
  private fun OwnInterfaceFrame(configured: Boolean) {
    MyApplicationTheme(mode = ThemeMode.DARK) {
      Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ForgedScreenSection(
          ui = ForgedAgentUi(state = ForgedScreenState.None, customUiEnabled = true, loaded = true),
          draft = "",
          configured = configured,
          onSetCustomUi = {},
          onDraftChange = {},
          onRequestChange = {},
          onRegenerate = {},
          onRetry = {},
          onAnswer = {},
          onRemove = {},
          onOpenUi = {},
          renderBundle = { _, _ -> },
        )
      }
    }
  }
}
