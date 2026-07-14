package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.util.formatRelativeDate
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.serialization.json.JsonObject

/**
 * Full-screen plan detail (Task 12, design spec section 15: "отдельная detail view с timeline
 * событий; программы показывают next due date") — objective/version/status header, the step
 * timeline (status, completion rule, last evidence, due date for a [PlanHorizon.PROGRAM] step), the
 * whole-plan criteria gate, the event audit trail, and pause/resume/cancel actions. Same full-screen
 * glass single-scroll shape as `com.aiopenpocket.ui.agents.detail.AgentDetailScreen` and
 * [PlanListScreen] — [PlanDetailScreen] itself is a dumb, state-in composable (Roborazzi-friendly,
 * no ViewModel) fed by the [PlanDetailRoute] wrapper below.
 *
 * Approve/request-changes are deliberately absent from this screen — see [PlanDetailViewModel]'s own
 * kdoc for why that stays a chat/[PlanCard]-only action.
 */
@Composable
fun PlanDetailScreen(
  state: PlanDetailState,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onNavigateBack: () -> Unit,
) {
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = "План",
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
      }
    },
  ) { padding ->
    if (state.isLoading && state.objective == null) {
      Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PurpleAccent)
      }
      return@AcademyCollapsingScaffold
    }
    if (state.notFound || state.objective == null) {
      Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(
          text = "План не найден",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
      return@AcademyCollapsingScaffold
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 40.dp)
        .testTag("plan_detail_scroll"),
    ) {
      Spacer(Modifier.height(12.dp))
      HeaderSection(state)
      if (state.actionError != null) {
        Spacer(Modifier.height(12.dp))
        ActionErrorBanner(state.actionError)
      }
      Spacer(Modifier.height(16.dp))
      ActionsRow(state, onPause = onPause, onResume = onResume, onCancel = onCancel)
      if (state.assumptions.isNotEmpty() || state.constraints.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        AssumptionsConstraintsSection(state)
      }
      Spacer(Modifier.height(24.dp))
      CriteriaSection(state.criteria)
      Spacer(Modifier.height(24.dp))
      StepsSection(state.steps, state.horizon)
      Spacer(Modifier.height(24.dp))
      EventsSection(state.events)
    }
  }
}

@Composable
private fun HeaderSection(state: PlanDetailState, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = state.objective.orEmpty(),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      state.status?.let { PlanStatusBadge(it) }
      Text(
        text = "v${state.version ?: 1} · ${state.horizon?.let(::planHorizonLabel) ?: ""}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
  }
}

@Composable
private fun ActionErrorBanner(message: String, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    )
  }
}

@Composable
private fun ActionsRow(
  state: PlanDetailState,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!state.canPause && !state.canResume && !state.canCancel) return
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.canPause) {
      OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).testTag("plan_pause")) {
        Text("Пауза")
      }
    }
    if (state.canResume) {
      OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f).testTag("plan_resume")) {
        Text("Продолжить")
      }
    }
    if (state.canCancel) {
      OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("plan_cancel")) {
        Text("Отменить")
      }
    }
  }
}

@Composable
private fun AssumptionsConstraintsSection(state: PlanDetailState, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    if (state.assumptions.isNotEmpty()) {
      SectionLabel("Предположения")
      Spacer(Modifier.height(6.dp))
      state.assumptions.forEach { BulletLine(it) }
      Spacer(Modifier.height(12.dp))
    }
    if (state.constraints.isNotEmpty()) {
      SectionLabel("Ограничения")
      Spacer(Modifier.height(6.dp))
      state.constraints.forEach { BulletLine(it) }
    }
  }
}

@Composable
private fun BulletLine(text: String, modifier: Modifier = Modifier) {
  Text(
    text = "• $text",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
private fun CriteriaSection(criteria: List<PlanCriterionRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Критерии выполнения")
    Spacer(Modifier.height(8.dp))
    if (criteria.isEmpty()) {
      EmptyStateCard("критериев не задано")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        criteria.forEach { row -> CriterionRow(row) }
      }
    }
  }
}

@Composable
private fun CriterionRow(row: PlanCriterionRow, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth().testTag("plan_detail_criterion_${row.id}"), shape = RoundedCornerShape(14.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val tint = if (row.satisfied) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
      Icon(
        imageVector = if (row.satisfied) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = if (row.satisfied) "Подтверждено" else "Не подтверждено",
        tint = tint,
        modifier = Modifier.padding(end = 10.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = row.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = if (row.required) "Обязательный" else "Необязательный",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }
    }
  }
}

@Composable
private fun StepsSection(steps: List<PlanStepRow>, horizon: PlanHorizon?, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Шаги")
    Spacer(Modifier.height(8.dp))
    if (steps.isEmpty()) {
      EmptyStateCard("шагов не задано")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, row -> StepRow(index + 1, row, showDueDate = horizon == PlanHorizon.PROGRAM) }
      }
    }
  }
}

@Composable
private fun StepRow(number: Int, row: PlanStepRow, showDueDate: Boolean, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth().testTag("plan_detail_step_${row.id}"), shape = RoundedCornerShape(14.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
          Text(
            text = "$number. ${row.title}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = row.instruction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
        StepStatusBadgeView(row.status)
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = "Критерий шага: ${completionRuleLabel(row.completionRuleType)} · попыток: ${row.attempts}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
      if (row.lastEvidenceSummary != null) {
        Text(
          text = "Последний результат: ${row.lastEvidenceSummary}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }
      if (showDueDate && row.nextDueAt != null) {
        Text(
          text = "Следующий срок: ${formatRelativeDate(row.nextDueAt)}",
          style = MaterialTheme.typography.labelSmall,
          color = PurpleAccent,
        )
      }
    }
  }
}

@Composable
private fun StepStatusBadgeView(status: StepStatus, modifier: Modifier = Modifier) {
  val color = stepStatusColor(status)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = stepStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = color)
  }
}

private fun completionRuleLabel(type: CompletionRuleType): String = when (type) {
  CompletionRuleType.TOOL_SUCCEEDED -> "успешный вызов инструмента"
  CompletionRuleType.EVIDENCE_PRESENT -> "есть подтверждающее свидетельство"
  CompletionRuleType.USER_CONFIRMED -> "подтверждение пользователя"
  CompletionRuleType.MODEL_ASSESSMENT -> "оценка модели"
}

@Composable
private fun EventsSection(events: List<PlanEvent>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("События")
    Spacer(Modifier.height(8.dp))
    if (events.isEmpty()) {
      EmptyStateCard("событий пока нет")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        events.forEach { event -> EventLine(event) }
      }
    }
  }
}

@Composable
private fun EventLine(event: PlanEvent, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = formatRelativeDate(event.createdAt),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
    Text(
      text = planEventLabel(event.type),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
  }
}

private fun planEventLabel(type: PlanEventType): String = when (type) {
  PlanEventType.CREATED -> "План создан"
  PlanEventType.PROPOSED -> "Предложена версия"
  PlanEventType.APPROVED -> "План одобрен"
  PlanEventType.REVISION_REQUESTED -> "Запрошены правки"
  PlanEventType.STEP_STARTED -> "Шаг начат"
  PlanEventType.STEP_VERIFIED -> "Шаг проверен"
  PlanEventType.STEP_BLOCKED -> "Шаг заблокирован"
  PlanEventType.PLAN_PAUSED -> "План поставлен на паузу"
  PlanEventType.PLAN_RESUMED -> "План возобновлён"
  PlanEventType.PLAN_COMPLETED -> "План завершён"
  PlanEventType.PLAN_CANCELLED -> "План отменён"
  PlanEventType.ERROR -> "Ошибка"
}

@Composable
private fun SectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = PurpleAccent)
}

@Composable
private fun EmptyStateCard(text: String, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth()) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      modifier = Modifier.padding(18.dp),
    )
  }
}

/**
 * Route wrapper for `plan/{planId}` (Task 12): obtains [PlanDetailViewModel] and drives it from the
 * nav-graph's `planId` argument.
 */
@Composable
fun PlanDetailRoute(
  planId: String,
  onNavigateBack: () -> Unit,
) {
  val viewModel: PlanDetailViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(planId) { viewModel.select(planId) }

  PlanDetailScreen(
    state = state,
    onPause = viewModel::pause,
    onResume = viewModel::resume,
    onCancel = viewModel::cancel,
    onNavigateBack = onNavigateBack,
  )
}

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
      completionRuleType = CompletionRuleType.TOOL_SUCCEEDED,
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
      completionRuleType = CompletionRuleType.USER_CONFIRMED,
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
  MyApplicationTheme {
    PlanDetailScreen(state = state, onPause = {}, onResume = {}, onCancel = {}, onNavigateBack = {})
  }
}
