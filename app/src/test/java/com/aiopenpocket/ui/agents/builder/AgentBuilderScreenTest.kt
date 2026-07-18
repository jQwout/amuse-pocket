package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Interaction tests for the STATELESS [AgentBuilderScreen] (state/callbacks only, no ViewModel —
 * `metroViewModel()` inside [AgentBuilderRoute] has nowhere to get a DI graph under Robolectric, the
 * same reason `AgentsGlassScreenshotTest` renders `AgentsPreviewScaffold` rather than `AgentsScreen`
 * itself). Covers the Task 20 plan's own required assertions (Next blocked on invalid identity,
 * Publish blocked on invalid hooks), the risk-consent gate, and the fixed testTag contract Task 24's
 * screenshot tests depend on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentBuilderScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** Passes every [AgentBuilderValidator]/[com.aiopenpocket.agent.core.agent.AgentValidator] rule on its own — each test starts here and breaks exactly one thing. */
  private fun validDraft(id: String = "agent-test-abc"): AgentDraft = AgentDraft(
    id = id,
    name = "Тестовый агент",
    description = "Короткое описание для теста.",
    iconKey = "book",
    systemPrompt = "Ты — тестовый агент.",
  )

  private fun render(
    state: AgentBuilderUiState,
    availableSkillIds: Set<String> = setOf("budget", "diary"),
    onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit = {},
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onExit: () -> Unit = {},
    onSetExecutionMode: (ExecutionMode) -> Unit = {},
    onSendTestMessage: (String) -> Unit = {},
    onPublish: () -> Unit = {},
    onArchive: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        AgentBuilderScreen(
          state = state,
          availableSkillIds = availableSkillIds,
          testStatus = null,
          testStreamingText = "",
          onUpdateDraft = onUpdateDraft,
          onBack = onBack,
          onNext = onNext,
          onExit = onExit,
          onSetExecutionMode = onSetExecutionMode,
          onSendTestMessage = onSendTestMessage,
          onPublish = onPublish,
          onArchive = onArchive,
        )
      }
    }
  }

  // --- Next / identity ---------------------------------------------------------------------------

  @Test
  fun next_is_disabled_when_identity_is_invalid() {
    // AgentDraft.new() is blank name/description/iconKey/systemPrompt - invalid on IDENTITY.
    val draft = AgentDraft.new(id = "agent-test-blank")
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.IDENTITY,
      errors = AgentBuilderValidator.validate(draft),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_next").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("agent_builder_back").assertIsEnabled()
  }

  @Test
  fun next_is_enabled_when_identity_is_valid() {
    val draft = validDraft()
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.IDENTITY,
      errors = AgentBuilderValidator.validate(draft),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_next").assertIsEnabled()
  }

  // --- Publish / hooks -----------------------------------------------------------------------------

  @Test
  fun publish_is_disabled_when_hooks_are_invalid() {
    // ALLOW is not in HookEngine.actionsAllowedFor(BEFORE_TURN) -> invalid_hook_action_for_event.
    val badHook = AgentHook(
      id = "hook-1",
      agentId = "agent-test-abc",
      event = HookEvent.BEFORE_TURN,
      conditionType = "always",
      conditionJson = "{}",
      actionType = HookActionType.ALLOW,
      actionJson = "{}",
      priority = 0,
      enabled = true,
    )
    val draft = validDraft().copy(hooks = listOf(badHook))
    val errors = AgentBuilderValidator.validate(draft)
    assertTrue("test setup should actually produce a hooks validation issue", errors.any { it.field == "hooks" })

    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.TEST,
      errors = errors,
      canPublish = AgentBuilderValidator.canPublish(errors),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsNotEnabled()
  }

  @Test
  fun publish_is_enabled_when_draft_is_fully_valid() {
    val draft = validDraft()
    val errors = AgentBuilderValidator.validate(draft)
    assertTrue("validDraft() should have zero validation issues", errors.isEmpty())

    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.TEST,
      errors = errors,
      canPublish = AgentBuilderValidator.canPublish(errors),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsEnabled()
  }

  // --- Risk consent gate (CAPABILITIES) -------------------------------------------------------------

  @Test
  fun risk_consent_dialog_blocks_a_destructive_tool_until_confirmed() {
    val draft = validDraft()
    val state = capabilitiesState(draft)
    var updateCalls = 0
    var lastToolNames: Set<String>? = null
    render(
      state = state,
      onUpdateDraft = { transform ->
        updateCalls += 1
        lastToolNames = transform(draft).toolNames
      },
    )

    // calendar: DESTRUCTIVE risk, in the default (non-"Advanced tools") set - must gate on consent.
    // performScrollTo обязателен: «Календарь» девятый в списке и лежит НИЖЕ сгиба, а клик по узлу за
    // пределами вьюпорта молча промахивается (диалог не открывался, а тест винил продуктовый код).
    composeTestRule.onNodeWithTag("agent_builder_tool_calendar").performScrollTo().performClick()
    composeTestRule.onNodeWithText("Включить «Календарь»?").assertExists()
    assertEquals("must not update the draft before the dialog is answered", 0, updateCalls)

    composeTestRule.onNodeWithText("Отмена").performClick()
    composeTestRule.onNodeWithText("Включить «Календарь»?").assertDoesNotExist()
    assertEquals("declining consent must never enable the tool", 0, updateCalls)
  }

  @Test
  fun risk_consent_dialog_enables_the_tool_once_confirmed() {
    val draft = validDraft()
    val state = capabilitiesState(draft)
    var lastToolNames: Set<String>? = null
    render(
      state = state,
      onUpdateDraft = { transform -> lastToolNames = transform(draft).toolNames },
    )

    composeTestRule.onNodeWithTag("agent_builder_tool_calendar").performScrollTo().performClick()
    composeTestRule.onNodeWithText("Включить").performClick()

    assertEquals(setOf("calendar"), lastToolNames)
  }

  @Test
  fun read_only_tool_never_shows_a_consent_dialog() {
    val draft = validDraft()
    val state = capabilitiesState(draft)
    var lastToolNames: Set<String>? = null
    render(
      state = state,
      onUpdateDraft = { transform -> lastToolNames = transform(draft).toolNames },
    )

    // render_card: RiskClass.READ - never requires consent.
    composeTestRule.onNodeWithTag("agent_builder_tool_render_card").performClick()

    assertEquals(setOf("render_card"), lastToolNames)
  }

  private fun capabilitiesState(draft: AgentDraft): AgentBuilderUiState = AgentBuilderUiState(
    draft = draft,
    step = BuilderStep.CAPABILITIES,
    errors = AgentBuilderValidator.validate(draft),
    isLoading = false,
  )

  // --- testTag contract (Task 24 depends on these) --------------------------------------------------

  @Test
  fun capabilities_step_exposes_tool_and_skill_test_tags() {
    val draft = validDraft()
    render(state = capabilitiesState(draft), availableSkillIds = setOf("budget"))

    composeTestRule.onNodeWithTag("agent_builder_tool_memory_tool").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_skill_budget").assertExists()
  }

  @Test
  fun hooks_step_exposes_hook_add_test_tag() {
    val draft = validDraft()
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.HOOKS,
      errors = AgentBuilderValidator.validate(draft),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_hook_add").assertExists()
  }

  @Test
  fun test_step_exposes_preview_live_test_and_publish_test_tags() {
    val draft = validDraft()
    val errors = AgentBuilderValidator.validate(draft)
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.TEST,
      errors = errors,
      canPublish = AgentBuilderValidator.canPublish(errors),
      executionMode = ExecutionMode.PREVIEW,
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_preview").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_live_test").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_publish").assertExists()
  }
}
