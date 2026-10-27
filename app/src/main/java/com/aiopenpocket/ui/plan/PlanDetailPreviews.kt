package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.compose.runtime.Composable
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonObject

// The fixed-state sample of the plan detail screen for the screenshot tests and previews. It lives in its own
// *Previews.kt file because its Russian sample data is not screen text, and tools/check.py (T6c) skips
// *Previews.kt by name. Left in PlanDetailScreen.kt it would be flagged, and the only ways out would be an
// exemption for the whole file (which would hide the real screen's strings) or a translated fixture that no
// longer matches the screenshot baselines.

// ==================================================================================================
// Превью экрана (скриншот-тесты) — фиксированный state, без реального ViewModel.
// ==================================================================================================

private const val PREVIEW_FIXED_TIMESTAMP = 1_700_000_000_000L

internal fun samplePlanDetailState(): PlanDetailState = PlanDetailState(
  isLoading = false,
  planId = "plan-1",
  agentId = "budget",
  conversationId = "conv-1",
  objective = "Составить бюджет на месяц и отслеживать траты по категориям",
  assumptions = listOf("Пользователь готов делиться суммами трат"),
  constraints = listOf("Не отправлять деньги, только советовать"),
  horizon = PlanHorizon.PROGRAM,
  status = PlanStatus.ACTIVE,
  version = 2,
  steps = listOf(
    PlanStepRow(
      id = "step-1",
      title = "Собрать расходы за месяц",
      instruction = "Прочитать сохранённые траты из памяти агента",
      dependsOn = emptySet(),
      status = StepStatus.COMPLETED,
      attempts = 1,
      nextDueAt = null,
      lastEvidenceSummary = "Найдено 42 траты за месяц",
    ),
    PlanStepRow(
      id = "step-2",
      title = "Напомнить проверить бюджет",
      instruction = "Раз в неделю спрашивать о тратах",
      dependsOn = setOf("step-1"),
      status = StepStatus.EXECUTING,
      attempts = 0,
      nextDueAt = PREVIEW_FIXED_TIMESTAMP,
      lastEvidenceSummary = null,
    ),
  ),
  criteria = listOf(
    PlanCriterionRow(id = "criterion-1", description = "Бюджет сохранён в памяти агента", required = true, satisfied = true),
    PlanCriterionRow(id = "criterion-2", description = "Пользователь подтвердил лимиты", required = false, satisfied = false),
  ),
  events = listOf(
    PlanEvent(
      id = "evt-2",
      planId = "plan-1",
      version = 2,
      stepId = null,
      type = PlanEventType.APPROVED,
      payload = JsonObject(emptyMap()),
      createdAt = PREVIEW_FIXED_TIMESTAMP,
    ),
    PlanEvent(
      id = "evt-1",
      planId = "plan-1",
      version = null,
      stepId = null,
      type = PlanEventType.CREATED,
      payload = JsonObject(emptyMap()),
      createdAt = PREVIEW_FIXED_TIMESTAMP - 1_000,
    ),
  ),
)

@Composable
internal fun PlanDetailPreviewScaffold(state: PlanDetailState = samplePlanDetailState()) {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    PlanDetailScreen(state = state, onPause = {}, onResume = {}, onCancel = {}, onNavigateBack = {})
  }
}
