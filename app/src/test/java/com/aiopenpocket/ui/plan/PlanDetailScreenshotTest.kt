package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baselines for the full-screen [PlanDetailScreen] (Task 12) — renders
 * [PlanDetailPreviewScaffold], the same fixed-state (no ViewModel) pattern as
 * `com.aiopenpocket.ui.agents.detail.AgentDetailScreenshotTest`. Baselines are written by the user
 * (`.\gradlew.bat :app:recordRoborazziDebug`), not generated here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PlanDetailScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun plan_detail_active_program_glass() {
    composeTestRule.setContent {
      PlanDetailPreviewScaffold()
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/plan_detail_active_program_glass.png")
  }

  @Test
  fun plan_detail_awaiting_approval_glass() {
    composeTestRule.setContent {
      PlanDetailPreviewScaffold(state = awaitingApprovalState())
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/plan_detail_awaiting_approval_glass.png")
  }

  /**
   * AWAITING_APPROVAL: пауза/продолжение бессмысленны (план ещё не исполняется — см.
   * [PlanDetailState.canPause]/[canResume]), а отменить предложение можно — [canCancel] разрешает
   * любой нетерминальный статус, и design spec section 8 перечисляет Cancel среди действий над
   * планом на одобрении. Approve/request-changes остаются только в чате (`PlanCard`'s own kdoc).
   */
  @Test
  fun plan_detail_awaiting_approval_offers_cancel_but_not_pause_or_resume() {
    composeTestRule.setContent {
      PlanDetailPreviewScaffold(state = awaitingApprovalState())
    }
    composeTestRule.onNodeWithTag("plan_pause").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plan_resume").assertDoesNotExist()
    composeTestRule.onNodeWithTag("plan_cancel").assertExists()
  }

  /** Pause/Cancel are wired to the real `onPause`/`onCancel` callbacks a route would pass in — proven here by simply not crashing on click against no-op lambdas; `PlanDetailViewModelTest` covers the actual dispatch to `PlanCommands`. */
  @Test
  fun plan_detail_pause_button_is_clickable_for_an_active_plan() {
    composeTestRule.setContent {
      PlanDetailPreviewScaffold()
    }
    composeTestRule.onNodeWithTag("plan_pause").assertExists()
    composeTestRule.onNodeWithTag("plan_pause").performClick()
  }

  private fun awaitingApprovalState(): PlanDetailState {
    val base = samplePlanDetailState()
    return base.copy(status = PlanStatus.AWAITING_APPROVAL, version = 1)
  }
}
