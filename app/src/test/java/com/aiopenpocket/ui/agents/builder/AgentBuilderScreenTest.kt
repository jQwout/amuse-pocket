package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.ToolTrace
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
    toolTrace: List<ToolTrace> = emptyList(),
    onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit = {},
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onExit: () -> Unit = {},
    onSetExecutionMode: (ExecutionMode) -> Unit = {},
    onSendTestMessage: (String) -> Unit = {},
    onPublish: () -> Unit = {},
    onArchive: () -> Unit = {},
    testPendingApproval: ToolApprovalRequest? = null,
    onResolveTestApproval: (Boolean) -> Unit = {},
    onOpenChat: (() -> Unit)? = null,
  ) {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        AgentBuilderScreen(
          state = state,
          availableSkillIds = availableSkillIds,
          testStatus = null,
          testStreamingText = "",
          testToolTrace = toolTrace,
          onUpdateDraft = onUpdateDraft,
          onBack = onBack,
          onNext = onNext,
          onExit = onExit,
          onSetExecutionMode = onSetExecutionMode,
          onSendTestMessage = onSendTestMessage,
          onPublish = onPublish,
          onArchive = onArchive,
          testPendingApproval = testPendingApproval,
          onResolveTestApproval = onResolveTestApproval,
          onOpenChat = onOpenChat,
        )
      }
    }
  }

  // --- Next / identity ---------------------------------------------------------------------------

  /**
   * Валидация «по кнопке»: на невалидном шаге «Далее» остаётся НАЖИМАЕМОЙ — она и есть триггер
   * проверки. Раньше кнопка гасла, и вместе с ошибками, показанными сразу на пустом черновике, это
   * давало мёртвый экран: нажать нечего, а чего от тебя хотят — непонятно. Сам отказ и показ ошибок
   * живут во ViewModel (`nextStep` поднимает `showErrors`) — см. AgentBuilderViewModelTest.
   */
  @Test
  fun next_stays_pressable_on_an_invalid_step() {
    // AgentDraft.new() is blank name/description/systemPrompt - invalid on IDENTITY.
    val draft = AgentDraft.new(id = "agent-test-blank")
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.IDENTITY,
      errors = AgentBuilderValidator.validate(draft),
      isLoading = false,
    )
    var nextClicks = 0
    render(state = state, onNext = { nextClicks++ })

    composeTestRule.onNodeWithTag("agent_builder_next").assertIsEnabled()
    composeTestRule.onNodeWithTag("agent_builder_next").performClick()
    assertEquals("нажатие должно дойти до ViewModel, а не гаситься кнопкой", 1, nextClicks)
    composeTestRule.onNodeWithTag("agent_builder_back").assertIsEnabled()
  }

  private fun blankIdentityState(showErrors: Boolean): AgentBuilderUiState {
    val draft = AgentDraft.new(id = "agent-test-blank")
    return AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.IDENTITY,
      errors = AgentBuilderValidator.validate(draft),
      isLoading = false,
      showErrors = showErrors,
    )
  }

  /** До нажатия «Далее» инлайн-ошибки молчат: свежий черновик не встречает пользователя красным. */
  @Test
  fun step_errors_are_hidden_before_next_is_pressed() {
    render(state = blankIdentityState(showErrors = false))

    composeTestRule.onNodeWithText("Имя агента не может быть пустым").assertDoesNotExist()
  }

  /** После нажатия «Далее» (ViewModel поднял showErrors) те же ошибки показаны. */
  @Test
  fun step_errors_appear_once_next_was_pressed() {
    render(state = blankIdentityState(showErrors = true))

    composeTestRule.onNodeWithText("Имя агента не может быть пустым").assertExists()
  }

  /**
   * Эталон стеклянной обвязки визарда (Liquid Glass): полотно шага занимает ВЕСЬ экран, а заголовок
   * с прогрессом и «Назад/Далее» — плавающие острова поверх него. До этого у конструктора не было
   * ни одного скриншот-теста, поэтому его вёрстка вообще ничем не была прикрыта.
   */
  @Test
  fun builder_glass_chrome_screenshot() {
    val draft = validDraft()
    render(
      state = AgentBuilderUiState(
        draft = draft,
        step = BuilderStep.IDENTITY,
        errors = AgentBuilderValidator.validate(draft),
        isLoading = false,
      ),
    )

    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_builder_glass.png")
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

  // --- custom skills: authored, listed, removable ----------------------------------------------------

  /**
   * An authored custom skill must be listed with a way to delete it. Without that row the only
   * evidence of a custom skill was its id in `skillIds`, which no control could reach — so a
   * mistyped one blocked «Далее»/«Опубликовать» with no way out but abandoning the draft.
   */
  @Test
  fun capabilities_step_lists_an_authored_custom_skill_and_offers_to_delete_it() {
    val draft = validDraft().let {
      it.copy(
        customSkills = listOf(CustomSkillDraft(id = "running-plan", name = "План бега", instructions = "# тело")),
        skillIds = it.skillIds + "running-plan",
      )
    }
    render(state = capabilitiesState(draft), availableSkillIds = setOf("budget"))

    composeTestRule.onNodeWithTag("agent_builder_own_skill_running-plan").assertExists()
    composeTestRule.onNodeWithContentDescription("Удалить навык").assertExists()
  }

  /** The memory rail moved onto «Возможности» (design spec 11.2) and now names the real namespace. */
  @Test
  fun capabilities_step_shows_the_agents_own_memory_namespace() {
    render(state = capabilitiesState(validDraft(id = "agent-mem-1")), availableSkillIds = setOf("budget"))

    composeTestRule.onNodeWithTag("agent_builder_shared_profile").assertExists()
    composeTestRule.onNodeWithText("agent:agent-mem-1:", substring = true).assertExists()
  }

  /** Publishing is gated on errors from OTHER steps, so the last step has to say which ones and where. */
  @Test
  fun test_step_lists_the_blocking_errors_by_step() {
    val draft = AgentDraft.new(id = "agent-test-blank")
    val errors = AgentBuilderValidator.validate(draft)
    val state = AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.TEST,
      errors = errors,
      canPublish = AgentBuilderValidator.canPublish(errors),
      isLoading = false,
    )
    render(state = state)

    composeTestRule.onNodeWithTag("agent_builder_publish").assertIsNotEnabled()
    composeTestRule.onNodeWithText("Основа: Имя агента не может быть пустым", substring = true).assertExists()
  }

  /**
   * Preview is only meaningful if the user can see WHICH tools the run reached for — otherwise a
   * PREVIEW run and a LIVE one look identical from the outside (design spec 11.2's "trace").
   */
  @Test
  fun test_step_shows_the_runs_tool_trace() {
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
    render(
      state = state,
      toolTrace = listOf(
        ToolTrace(1, "calendar", emptyMap(), ToolResult.Success("Preview mode: 'calendar' was not executed.")),
      ),
    )

    composeTestRule.onNodeWithTag("agent_builder_tool_trace").assertExists()
    // Журнал зовёт тул по-человечески, сырой id `calendar` на экран не попадает.
    composeTestRule.onNodeWithText("Календарь", substring = true).assertExists()
    composeTestRule.onNodeWithText("calendar", substring = true).assertDoesNotExist()
  }

  private fun testStepState(draft: AgentDraft = validDraft(), justPublished: Boolean = false): AgentBuilderUiState {
    val errors = AgentBuilderValidator.validate(draft)
    return AgentBuilderUiState(
      draft = draft,
      step = BuilderStep.TEST,
      errors = errors,
      canPublish = AgentBuilderValidator.canPublish(errors),
      isLoading = false,
      justPublished = justPublished,
    )
  }

  /**
   * Хук «Требовать подтверждение» ставит вызов на ожидание и в тестовом прогоне. Без бара на шаге
   * «Проверка» ответить было негде, и ход молча висел две минуты до таймаута координатора.
   */
  @Test
  fun test_step_shows_the_tool_approval_bar_and_forwards_the_answer() {
    var answer: Boolean? = null
    render(
      state = testStepState(),
      testPendingApproval = ToolApprovalRequest(
        requestId = "req-1",
        runId = "run-1",
        conversationId = "conv-test",
        toolName = "calendar",
        riskClass = RiskClass.DESTRUCTIVE,
        arguments = emptyMap(),
      ),
      onResolveTestApproval = { answer = it },
    )

    composeTestRule.onNodeWithText("Агенту нужно разрешение").assertExists()
    // Бар называет тул так же, как конструктор, а не сырым `calendar`.
    composeTestRule.onNodeWithText("Календарь").assertExists()
    composeTestRule.onNodeWithText("Отклонить").performScrollTo().performClick()
    assertEquals(false, answer)
  }

  /** После успешной публикации — подтверждение и переход в чат агента. */
  @Test
  fun test_step_confirms_a_publish_and_offers_to_open_the_chat() {
    var openChatClicks = 0
    render(
      state = testStepState(
        draft = validDraft().copy(status = AgentStatus.PUBLISHED, configVersion = 1L),
        justPublished = true,
      ),
      onOpenChat = { openChatClicks++ },
    )

    composeTestRule.onNodeWithTag("agent_builder_published").assertExists()
    composeTestRule.onNodeWithTag("agent_builder_open_chat").performScrollTo().performClick()
    assertEquals(1, openChatClicks)
  }
}
