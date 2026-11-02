package com.aiopenpocket.ui.forge

// @trace cloud-forged-screens-40d702

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * The full-screen forged interface ([AgentUiScreen], pure: state in, callbacks out). A bundle is drawn
 * by a STAND-IN text, not by Ketoy, which needs a real runtime Robolectric cannot give: the baselines
 * guard the frame around it (header with close / name / Chat, the aurora, the status card), never the
 * guest screen's pixels. Each baseline asserts a tag only its state renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentUiScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun backToDefault() {
    AppLanguage.code = AppLanguage.RU
  }

  private val ready = ForgedScreenState.Ready("job-ui-3", "a water tracker", "WaterScreen", 2, "job-ui-3.ktx")

  private fun state(ui: ForgedAgentUi) = AgentUiState(agentId = "agent-ui", title = "Water Tracker", ui = ui)

  @Composable
  private fun Screen(
    ui: ForgedAgentUi,
    onOpenChat: () -> Unit = {},
    onOpenAgentDetail: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
  ) {
    MyApplicationTheme(mode = ThemeMode.DARK) {
      AgentUiScreen(
        state = state(ui),
        onOpenChat = onOpenChat,
        onOpenAgentDetail = onOpenAgentDetail,
        onNavigateBack = onNavigateBack,
        renderBundle = { r, _ ->
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ketoy stand-in: ${r.entryPoint} v${r.version}", color = MaterialTheme.colorScheme.onBackground)
          }
        },
      )
    }
  }

  @Test
  fun agent_ui_building() {
    composeTestRule.setContent {
      Screen(ForgedAgentUi(state = ForgedScreenState.Working("job-ui-1", "a water tracker", "compiling"), loaded = true))
    }
    composeTestRule.onNodeWithTag("agent_ui_status").assertIsDisplayed()
    composeTestRule.onNodeWithTag("agent_ui_bundle").assertDoesNotExist()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_ui_building.png")
  }

  @Test
  fun agent_ui_ready() {
    composeTestRule.setContent {
      Screen(ForgedAgentUi(state = ready, bundle = byteArrayOf(1, 2, 3), customUiEnabled = true, loaded = true))
    }
    composeTestRule.onNodeWithTag("agent_ui_bundle").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ketoy stand-in: WaterScreen v2").assertIsDisplayed()
    composeTestRule.onNodeWithTag("agent_ui_status").assertDoesNotExist()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_ui_ready.png")
  }

  @Test
  fun `a ready state whose bundle is missing does not render the bundle`() {
    composeTestRule.setContent {
      Screen(ForgedAgentUi(state = ready, bundle = null, customUiEnabled = true, loaded = true))
    }
    composeTestRule.onNodeWithTag("agent_ui_bundle").assertDoesNotExist()
    composeTestRule.onNodeWithTag("agent_ui_status").assertIsDisplayed()
  }

  @Test
  fun `a failure says why, and the chat is one tap away`() {
    var chats = 0
    composeTestRule.setContent {
      Screen(
        ForgedAgentUi(state = ForgedScreenState.Failed("job-ui-4", "req", "kotlinc-exploded-mock"), loaded = true),
        onOpenChat = { chats++ },
      )
    }
    composeTestRule.onNodeWithText("kotlinc-exploded-mock").assertIsDisplayed()

    composeTestRule.onNodeWithTag("agent_ui_open_chat").performClick()

    assertEquals(1, chats)
  }

  @Test
  fun `the close cross and the agent card button call back`() {
    var closed = 0
    var cards = 0
    composeTestRule.setContent {
      Screen(
        ForgedAgentUi(state = ForgedScreenState.NeedsInfo("job-ui-5", "req", "q?"), loaded = true),
        onOpenAgentDetail = { cards++ },
        onNavigateBack = { closed++ },
      )
    }
    composeTestRule.onNodeWithText("The agent has a question - answer it on the agent's card.", substring = true).assertIsDisplayed()

    composeTestRule.onNodeWithTag("agent_ui_close").performClick()
    composeTestRule.onNodeWithText("Agent card").performClick()

    assertEquals(1, closed)
    assertEquals(1, cards)
  }

  @Test
  fun `a loaded agent with no interface says so`() {
    composeTestRule.setContent {
      Screen(ForgedAgentUi(state = ForgedScreenState.None, loaded = true))
    }
    composeTestRule.onNodeWithText("This agent has no interface of its own yet.").assertIsDisplayed()
  }
}
