package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.ui.agents.builder.components.ToolCatalog
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The review screen: [AgentBuilderScreen] with `reviewMode = true`, the page the builder chat sends the person
 * to. Stateless like [AgentBuilderScreenTest] (no ViewModel under Robolectric), but [render] keeps the draft in
 * Compose state and validates it the way `AgentBuilderViewModel.updateDraft` does, so a tap on a checkbox is
 * seen as a change of what is drawn — which is what "tools reflect and toggle the draft" has to prove.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentReviewScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun validDraft(): AgentDraft = AgentDraft(
    id = "agent-test-abc",
    name = "Тестовый агент",
    description = "Короткое описание для теста.",
    iconKey = "book",
    systemPrompt = "Ты — тренер по бегу. Отвечай списком, коротко и дружелюбно.",
    toolNames = setOf("memory_tool", "render_card"),
  )

  /** The draft currently on screen: edits from the screen land here, as they would in the ViewModel. */
  private var draft by mutableStateOf(validDraft())

  private fun render(
    initial: AgentDraft = validDraft(),
    reviewMode: Boolean = true,
    onExit: () -> Unit = {},
    onPublish: () -> Unit = {},
    onEditStepByStep: (BuilderStep) -> Unit = {},
  ) {
    draft = initial
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        val errors = AgentBuilderValidator.validate(draft)
        AgentBuilderScreen(
          state = AgentBuilderUiState(
            draft = draft,
            errors = errors,
            canPublish = AgentBuilderValidator.canPublish(errors),
            isLoading = false,
            reviewMode = reviewMode,
          ),
          availableSkillIds = setOf("budget"),
          testStatus = null,
          testStreamingText = "",
          testToolTrace = emptyList(),
          onUpdateDraft = { transform -> draft = transform(draft) },
          onBack = {},
          onNext = {},
          onExit = onExit,
          onSetExecutionMode = {},
          onSendTestMessage = {},
          onPublish = onPublish,
          onArchive = {},
          onEditStepByStep = onEditStepByStep,
        )
      }
    }
  }

  // --- opens on the review -----------------------------------------------------------------------------

  /** reviewMode swaps the wizard for the one-page review: no step progress, no "Next". */
  @Test
  fun review_mode_shows_the_review_page_instead_of_step_one() {
    render(reviewMode = true)

    composeTestRule.onNodeWithTag("agent_builder_review").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_next").assertDoesNotExist()
  }

  @Test
  fun without_review_mode_the_wizard_opens_as_before() {
    render(reviewMode = false)

    composeTestRule.onNodeWithTag("agent_builder_review").assertDoesNotExist()
    composeTestRule.onNodeWithTag("agent_builder_next").assertExists()
  }

  /** Every part of the draft is on the one page, nothing behind a tap that the owner wants in sight. */
  @Test
  fun review_shows_every_section_and_keeps_tools_and_model_open() {
    render()

    listOf("identity", "instructions", "tools", "modes", "model", "rules").forEach { section ->
      composeTestRule.onNodeWithTag("agent_builder_review_$section").assertExists()
    }
    // Present in the tree = not folded away (a folded section composes none of its content).
    ToolCatalog.default.forEach { tool ->
      composeTestRule.onNodeWithTag("agent_builder_tool_${tool.name}").assertExists()
    }
    composeTestRule.onNodeWithTag("builder_model_default").assertExists()
    composeTestRule.onNodeWithTag("builder_model_${AgentModel.WONDER.key}").assertExists()
    composeTestRule.onNodeWithTag("builder_model_${AgentModel.SPARK.key}").assertExists()
    // The secondary blocks are folded by default.
    composeTestRule.onNodeWithTag("agent_builder_preview").assertDoesNotExist()
    composeTestRule.onNodeWithTag("agent_builder_skill_budget").assertDoesNotExist()
  }

  @Test
  fun folded_sections_open_on_a_tap() {
    render()

    composeTestRule.onNodeWithText("Пробный запуск").performScrollTo().performClick()

    composeTestRule.onNodeWithTag("agent_builder_preview").assertExists()
  }

  // --- tools ----------------------------------------------------------------------------------------

  @Test
  fun tool_checkboxes_reflect_the_draft() {
    render(initial = validDraft().copy(toolNames = setOf("memory_tool", "render_card")))

    composeTestRule.onNodeWithTag("agent_builder_tool_memory_tool").performScrollTo().assertIsOn()
    composeTestRule.onNodeWithTag("agent_builder_tool_render_card").performScrollTo().assertIsOn()
    composeTestRule.onNodeWithTag("agent_builder_tool_file_tool").performScrollTo().assertIsOff()
  }

  @Test
  fun tapping_a_tool_checkbox_toggles_it_in_the_draft() {
    render()

    composeTestRule.onNodeWithTag("agent_builder_tool_load_skill").performScrollTo().performClick()
    composeTestRule.runOnIdle {
      assertEquals(setOf("memory_tool", "render_card", "load_skill"), draft.toolNames)
    }
    composeTestRule.onNodeWithTag("agent_builder_tool_load_skill").assertIsOn()

    composeTestRule.onNodeWithTag("agent_builder_tool_render_card").performScrollTo().performClick()
    composeTestRule.runOnIdle {
      assertEquals(setOf("memory_tool", "load_skill"), draft.toolNames)
    }
    composeTestRule.onNodeWithTag("agent_builder_tool_render_card").assertIsOff()
  }

  /** The review is not a back door around the risk rail: a destructive tool still asks before it is ticked. */
  @Test
  fun ticking_a_destructive_tool_in_the_review_still_asks_first() {
    render()

    composeTestRule.onNodeWithTag("agent_builder_tool_calendar").performScrollTo().performClick()

    composeTestRule.onNodeWithText("Включить «Календарь»?").assertExists()
    composeTestRule.runOnIdle { assertTrue("calendar" !in draft.toolNames) }
  }

  // --- validation and Publish -------------------------------------------------------------------------

  @Test
  fun publish_is_enabled_for_a_valid_draft() {
    render()

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsEnabled()
    composeTestRule.onNodeWithTag("agent_builder_review_blocked").assertDoesNotExist()
  }

  @Test
  fun publish_is_disabled_and_the_error_is_inline_while_the_draft_is_invalid() {
    render(initial = validDraft().copy(name = ""))

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsNotEnabled()
    // Shown at once, under the field it is about — without a prior "Next" like in the wizard.
    composeTestRule.onNodeWithText("Имя агента не может быть пустым").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_review_blocked").assertExists()
  }

  @Test
  fun publish_becomes_enabled_once_the_error_is_fixed() {
    render(initial = validDraft().copy(name = ""))
    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsNotEnabled()

    composeTestRule.runOnIdle { draft = draft.copy(name = "Тренер") }

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsEnabled()
  }

  @Test
  fun tapping_publish_calls_publish() {
    var published = 0
    render(onPublish = { published++ })

    composeTestRule.onNodeWithTag("agent_builder_publish").performClick()

    assertEquals(1, published)
  }

  // --- leaving ----------------------------------------------------------------------------------------

  @Test
  fun back_to_chat_navigates_back_and_publishes_nothing() {
    var exits = 0
    var published = 0
    render(onExit = { exits++ }, onPublish = { published++ })

    composeTestRule.onNodeWithTag("agent_builder_back_to_chat").performClick()

    assertEquals(1, exits)
    assertEquals(0, published)
  }

  @Test
  fun edit_step_by_step_and_edit_rules_ask_for_the_right_wizard_step() {
    val asked = mutableListOf<BuilderStep>()
    val hook = AgentHook(
      id = "hook-1",
      agentId = "agent-test-abc",
      event = HookEvent.BEFORE_TURN,
      conditionType = "always",
      conditionJson = "{}",
      actionType = HookActionType.ADD_INSTRUCTION,
      actionJson = "{\"text\":\"Коротко.\"}",
      priority = 0,
      enabled = true,
    )
    render(initial = validDraft().copy(hooks = listOf(hook)), onEditStepByStep = { asked += it })

    composeTestRule.onNodeWithTag("agent_builder_edit_steps").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("agent_builder_edit_rules").performScrollTo().performClick()

    assertEquals(listOf(BuilderStep.IDENTITY, BuilderStep.HOOKS), asked)
  }

  /** The baseline of the review page: every section at once on a drafted agent. */
  @Test
  fun review_screenshot() {
    render()

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_review_glass.png")
  }

  /** The tools block scrolled into view: the ticked and unticked checkboxes the owner wants to see at once. */
  @Test
  fun review_tools_screenshot() {
    render()

    composeTestRule.onNodeWithTag("agent_builder_review_tools").performScrollTo()

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_review_tools_glass.png")
  }

  /** The model chips and the folded blocks below the tools. */
  @Test
  fun review_model_and_rules_screenshot() {
    render()

    composeTestRule.onNodeWithTag("agent_builder_review_model").performScrollTo()

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/agent_review_model_glass.png")
  }
}
