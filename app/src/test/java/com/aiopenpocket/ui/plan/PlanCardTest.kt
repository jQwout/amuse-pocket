package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [PlanCard] is a dumb, state-in composable (no ViewModel, no Room) — [render] feeds it a fixed
 * [PlanSnapshot] through a fake `observePlan` lambda, mirroring how `AgentBuilderScreenTest` renders
 * `AgentBuilderScreen` directly. Covers Task 12's own testTag contract
 * (`plan_card_<id>`/`plan_approve`/`plan_request_changes`/`plan_pause`/`plan_resume`/`plan_cancel`/
 * `plan_open_detail`) and proves the card never invents a second approval mechanism: Approve/Request
 * changes/Pause/Resume/Cancel all fire straight back through the plain callbacks a real `ChatScreen`
 * maps onto `ChatIntent.ApprovePlan`/`RequestPlanChanges`/`PausePlan`/`ResumePlan`/`CancelPlan`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PlanCardTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var approvedArgs: Pair<String, Int>? = null
  private var requestChangesArgs: Triple<String, Int, String>? = null
  private var pausedPlanId: String? = null
  private var resumedPlanId: String? = null
  private var cancelledPlanId: String? = null
  private var openedDetailPlanId: String? = null

  private fun render(snapshot: PlanSnapshot) {
    val flow: Flow<PlanSnapshot?> = MutableStateFlow(snapshot)
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        PlanCard(
          planId = snapshot.plan.id,
          observePlan = { flow },
          onApprove = { planId, version -> approvedArgs = planId to version },
          onRequestChanges = { planId, version, feedback -> requestChangesArgs = Triple(planId, version, feedback) },
          onPause = { planId -> pausedPlanId = planId },
          onResume = { planId -> resumedPlanId = planId },
          onCancel = { planId -> cancelledPlanId = planId },
          onOpenDetail = { planId -> openedDetailPlanId = planId },
        )
      }
    }
  }

  // --- fixtures (mirrors PlanRepositoryTest's own builder style) --------------------------------

  private fun step(
    id: String = "step-1",
    title: String = "Собрать расходы",
  ) = PlanStep(
    id = id,
    title = title,
    instruction = "Instruction $id",
    dependsOn = emptySet(),
    expectedTools = setOf("memory_tool"),
    schedule = null,
    completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
  )

  private fun criterion(id: String = "criterion-1", required: Boolean = true) = PlanCriterion(
    id = id,
    description = "Description $id",
    verifier = CriterionVerifier(type = VerifierType.MEMORY_FACT),
    required = required,
  )

  private fun version(
    planId: String = "plan-1",
    version: Int = 1,
    steps: List<PlanStep> = listOf(step()),
    criteria: List<PlanCriterion> = listOf(criterion()),
  ) = PlanVersion(
    planId = planId,
    version = version,
    objective = "Составить бюджет на месяц",
    assumptions = emptyList(),
    constraints = emptyList(),
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  private fun plan(
    id: String = "plan-1",
    status: PlanStatus = PlanStatus.AWAITING_APPROVAL,
    horizon: PlanHorizon = PlanHorizon.SINGLE_RUN,
  ) = AgentPlan(
    id = id,
    agentId = "budget",
    conversationId = "conv-1",
    horizon = horizon,
    status = status,
    approvedVersion = null,
    activeStepId = null,
    createdAt = 1_000L,
    updatedAt = 1_000L,
  )

  private fun stepState(
    planId: String = "plan-1",
    version: Int = 1,
    stepId: String = "step-1",
    status: StepStatus = StepStatus.PENDING,
  ) = PlanStepState(
    planId = planId,
    version = version,
    stepId = stepId,
    status = status,
    attempts = 0,
    nextDueAt = null,
    startedAt = null,
    completedAt = null,
  )

  // --- AWAITING_APPROVAL: proposal preview --------------------------------------------------------

  @Test
  fun awaiting_approval_shows_objective_and_approve_request_changes_actions() {
    val snapshot = PlanSnapshot(
      plan = plan(status = PlanStatus.AWAITING_APPROVAL),
      version = version(),
      stepStates = emptyList(),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_card_plan-1").assertExists()
    composeTestRule.onNodeWithText("Составить бюджет на месяц").assertExists()
    composeTestRule.onNodeWithTag("plan_approve").assertExists()
    composeTestRule.onNodeWithTag("plan_request_changes").assertExists()
    composeTestRule.onNodeWithTag("plan_open_detail").assertExists()
    composeTestRule.onNodeWithTag("plan_pause").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plan_resume").assertDoesNotExist()
  }

  @Test
  fun approve_dispatches_the_pending_plan_id_and_version() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-42", status = PlanStatus.AWAITING_APPROVAL),
      version = version(planId = "plan-42", version = 3),
      stepStates = emptyList(),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_approve").performClick()

    assertEquals("plan-42" to 3, approvedArgs)
    assertNull("approve must never also fire request-changes", requestChangesArgs)
  }

  @Test
  fun request_changes_dispatches_the_typed_feedback_field() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-42", status = PlanStatus.AWAITING_APPROVAL),
      version = version(planId = "plan-42", version = 2),
      stepStates = emptyList(),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_request_changes").performClick()

    assertEquals("plan-42", requestChangesArgs?.first)
    assertEquals(2, requestChangesArgs?.second)
    assertNull("request-changes must never also fire approve", approvedArgs)
  }

  @Test
  fun open_detail_dispatches_the_shown_plan_id() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-42", status = PlanStatus.AWAITING_APPROVAL),
      version = version(planId = "plan-42"),
      stepStates = emptyList(),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_open_detail").performClick()

    assertEquals("plan-42", openedDetailPlanId)
  }

  // --- ACTIVE / PAUSED: compact progress -----------------------------------------------------------

  @Test
  fun active_plan_shows_pause_and_cancel_but_not_resume_or_approval_actions() {
    val steps = listOf(step(id = "step-1"), step(id = "step-2", title = "Напомнить о бюджете"))
    val snapshot = PlanSnapshot(
      plan = plan(status = PlanStatus.ACTIVE),
      version = version(steps = steps),
      stepStates = listOf(
        stepState(stepId = "step-1", status = StepStatus.COMPLETED),
        stepState(stepId = "step-2", status = StepStatus.EXECUTING),
      ),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_pause").assertExists()
    composeTestRule.onNodeWithTag("plan_cancel").assertExists()
    composeTestRule.onNodeWithTag("plan_resume").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plan_approve").assertDoesNotExist()
    composeTestRule.onNodeWithText("Текущий шаг: Напомнить о бюджете").assertExists()
    composeTestRule.onNodeWithText("1/2 шагов").assertExists()
  }

  @Test
  fun pause_dispatches_the_active_plan_id() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-7", status = PlanStatus.ACTIVE),
      version = version(planId = "plan-7"),
      stepStates = listOf(stepState(planId = "plan-7", status = StepStatus.EXECUTING)),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_pause").performClick()

    assertEquals("plan-7", pausedPlanId)
  }

  @Test
  fun paused_plan_shows_resume_and_cancel_but_not_pause() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-7", status = PlanStatus.PAUSED),
      version = version(planId = "plan-7"),
      stepStates = listOf(stepState(planId = "plan-7", status = StepStatus.EXECUTING)),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_resume").assertExists()
    composeTestRule.onNodeWithTag("plan_cancel").assertExists()
    composeTestRule.onNodeWithTag("plan_pause").assertDoesNotExist()
  }

  @Test
  fun resume_dispatches_the_paused_plan_id() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-7", status = PlanStatus.PAUSED),
      version = version(planId = "plan-7"),
      stepStates = listOf(stepState(planId = "plan-7", status = StepStatus.EXECUTING)),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_resume").performClick()

    assertEquals("plan-7", resumedPlanId)
  }

  @Test
  fun cancel_dispatches_the_shown_plan_id_from_the_progress_branch() {
    val snapshot = PlanSnapshot(
      plan = plan(id = "plan-7", status = PlanStatus.ACTIVE),
      version = version(planId = "plan-7"),
      stepStates = listOf(stepState(planId = "plan-7", status = StepStatus.EXECUTING)),
      evidence = emptyList(),
    )
    render(snapshot)

    composeTestRule.onNodeWithTag("plan_cancel").performClick()

    assertEquals("plan-7", cancelledPlanId)
  }
}
