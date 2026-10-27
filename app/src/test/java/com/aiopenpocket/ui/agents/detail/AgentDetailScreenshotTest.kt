package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baselines of the full-screen [AgentDetailScreen] (Task 21) — a new baseline replacing the
 * deleted baselines of the old inspector sheet (`agent_inspector_*_glass.png`, deleted alongside
 * `AgentInspectorSheet`/`AgentInspectorViewModel`). Renders [AgentDetailPreviewScaffold] — the same
 * visual frame as in production, with a fixed (not via a ViewModel) [AgentDetailState].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentDetailScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun agent_detail_glass() {
    composeTestRule.setContent {
      AgentDetailPreviewScaffold()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_glass.png")
  }

  /**
   * A long (>40 lines) system prompt is expanded by default (see the Task 21 brief) — the baseline is taken
   * BEFORE the click, proving that the whole text is in the scroll flow at once and collapsing is an
   * explicit user action, not the default.
   */
  @Test
  fun agent_detail_long_prompt_expanded_by_default_glass() {
    composeTestRule.setContent {
      AgentDetailPreviewScaffold(state = longPromptState())
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_long_prompt_expanded_glass.png")
  }

  @Test
  fun agent_detail_long_prompt_collapsed_glass() {
    composeTestRule.setContent {
      AgentDetailPreviewScaffold(state = longPromptState())
    }
    composeTestRule.onNodeWithTag("agent_detail_prompt_toggle").performClick()
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_long_prompt_collapsed_glass.png")
  }

  /** A focus on the "Tools" section (restyled to the mockup) — it is below the fold, a full-screen frame does not catch it. */
  @Test
  fun agent_detail_tools_section() {
    composeTestRule.setContent {
      ToolsSectionPreview()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_tools_section.png")
  }

  /** A focus on the "System prompt" section (restyle: header + card + inline chevron). */
  @Test
  fun agent_detail_prompt_section() {
    composeTestRule.setContent { PromptSectionPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_prompt_section.png")
  }

  /** A focus on the "Heartbeat" section (restyle: header + rows of title/lime schedule/description). */
  @Test
  fun agent_detail_heartbeat_section() {
    composeTestRule.setContent { HeartbeatSectionPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_heartbeat_section.png")
  }

  /** A focus on the stat tiles (restyle: an uppercase label on top, a large value below). */
  @Test
  fun agent_detail_stat_tiles() {
    composeTestRule.setContent { StatTilesPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_stat_tiles.png")
  }

  /** A focus on the "Hooks" section (restyle: a collapsible summary card "N of M enabled"). */
  @Test
  fun agent_detail_hooks_section() {
    composeTestRule.setContent { HooksSectionPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_hooks_section.png")
  }

  private fun longPromptState(): AgentDetailState {
    val base = samplePreviewState()
    val definition = requireNotNull(base.definition)
    val longPrompt = (1..50).joinToString("\n") { line -> "Строка препромпта номер $line — часть длинной инструкции агента." }
    return base.copy(definition = definition.copy(systemPrompt = longPrompt))
  }
}
