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
 * Скриншот-эталоны полноэкранного [AgentDetailScreen] (Task 21) — новый baseline, сменяющий
 * удалённые эталоны старой инспектор-шторки (`agent_inspector_*_glass.png`, deleted alongside
 * `AgentInspectorSheet`/`AgentInspectorViewModel`). Рендерит [AgentDetailPreviewScaffold] — тот же
 * визуальный каркас, что и в бою, с фиксированным (не через ViewModel) [AgentDetailState].
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
   * Длинный (>40 строк) препромпт по умолчанию развёрнут (см. бриф Task 21) — эталон снят ДО клика,
   * доказывая, что весь текст сразу в потоке скролла, а свёртка — явное действие пользователя, не
   * дефолт.
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

  /** Фокус на секции «Инструменты» (рестайл под макет) — она ниже сгиба, полноэкранный кадр её не ловит. */
  @Test
  fun agent_detail_tools_section() {
    composeTestRule.setContent {
      ToolsSectionPreview()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_tools_section.png")
  }

  /** Фокус на секции «Препромпт» (рестайл: header + карточка + инлайн-шеврон). */
  @Test
  fun agent_detail_prompt_section() {
    composeTestRule.setContent { PromptSectionPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_prompt_section.png")
  }

  /** Фокус на секции «Heartbeat» (рестайл: header + строки title/лайм-расписание/описание). */
  @Test
  fun agent_detail_heartbeat_section() {
    composeTestRule.setContent { HeartbeatSectionPreview() }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_detail_heartbeat_section.png")
  }

  private fun longPromptState(): AgentDetailState {
    val base = samplePreviewState()
    val definition = requireNotNull(base.definition)
    val longPrompt = (1..50).joinToString("\n") { line -> "Строка препромпта номер $line — часть длинной инструкции агента." }
    return base.copy(definition = definition.copy(systemPrompt = longPrompt))
  }
}
