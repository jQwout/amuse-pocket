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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Full-screen roster of every plan proposed for one agent (Task 12, route `plans/{agentId}`) — the
 * plan analogue of `com.aiopenpocket.ui.agents.detail.AgentDetailScreen`: same full-screen glass,
 * single-scroll shape ([AcademyCollapsingScaffold] + a plain `Column`/`verticalScroll`), same
 * "dumb, state-in" composable split (this file's [PlanListScreen] takes [PlanListState] directly so
 * Roborazzi can render it with a fixed sample state, exactly like `AgentDetailPreviewScaffold`) plus
 * a [PlanListRoute] wrapper that owns the real [PlanListViewModel].
 */
@Composable
fun PlanListScreen(
  state: PlanListState,
  onOpenPlan: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = "Планы",
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
      }
    },
  ) { padding ->
    val total = state.active.size + state.paused.size + state.completed.size
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        // Отступы каркаса стоят ПОСЛЕ verticalScroll: иначе они инсетят вьюпорт и контент не
        // заезжает под плавающие острова (стеклу нечего размывать). Тот же приём, что в AboutMeStep.
        .padding(padding)
        .padding(horizontal = 20.dp)
        .padding(bottom = 40.dp)
        .testTag("plan_list_scroll"),
    ) {
      Spacer(Modifier.height(12.dp))
      if (state.isLoading && total == 0) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return@AcademyCollapsingScaffold
      }
      if (total == 0) {
        EmptyStateCard("у этого агента ещё нет планов")
        return@AcademyCollapsingScaffold
      }
      CountsRow(state)
      Spacer(Modifier.height(20.dp))
      PlanListSection(title = "Активные", rows = state.active, onOpenPlan = onOpenPlan)
      Spacer(Modifier.height(24.dp))
      PlanListSection(title = "На паузе", rows = state.paused, onOpenPlan = onOpenPlan)
      Spacer(Modifier.height(24.dp))
      PlanListSection(title = "Завершённые", rows = state.completed, onOpenPlan = onOpenPlan)
    }
  }
}

@Composable
private fun CountsRow(state: PlanListState, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CountTile(label = "Активные", count = state.active.size, modifier = Modifier.weight(1f))
    CountTile(label = "На паузе", count = state.paused.size, modifier = Modifier.weight(1f))
    CountTile(label = "Завершённые", count = state.completed.size, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun CountTile(label: String, count: Int, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
  }
}

@Composable
private fun PlanListSection(title: String, rows: List<PlanListRow>, onOpenPlan: (String) -> Unit, modifier: Modifier = Modifier) {
  if (rows.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth()) {
    Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      rows.forEach { row -> PlanListRowCard(row, onClick = { onOpenPlan(row.planId) }) }
    }
  }
}

@Composable
private fun PlanListRowCard(row: PlanListRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth().testTag("plan_list_row_${row.planId}"),
    shape = RoundedCornerShape(14.dp),
    onClick = onClick,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
        Text(
          text = row.objective,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${row.stepsCompleted}/${row.stepsTotal} шагов",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
      PlanStatusBadge(row.status)
    }
  }
}

@Composable
internal fun PlanStatusBadge(status: PlanStatus, modifier: Modifier = Modifier) {
  val color = planStatusColor(status)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = planStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = color)
  }
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
 * Route wrapper for `plans/{agentId}` (Task 12): obtains [PlanListViewModel] and drives it from the
 * nav-graph's `agentId` argument — mirrors `com.aiopenpocket.ui.agents.detail.AgentDetailRoute`'s own
 * `LaunchedEffect(agentId) { viewModel.select(agentId) }` pattern.
 */
@Composable
fun PlanListRoute(
  agentId: String,
  onOpenPlan: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: PlanListViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(agentId) { viewModel.select(agentId) }

  PlanListScreen(
    state = state,
    onOpenPlan = onOpenPlan,
    onNavigateBack = onNavigateBack,
  )
}

// ==================================================================================================
// Превью экрана (скриншот-тесты) — тот же приём, что и AgentDetailPreviewScaffold: фиксированный
// state, без реального ViewModel.
// ==================================================================================================

private const val PREVIEW_FIXED_TIMESTAMP = 1_700_000_000_000L

internal fun samplePlanListState(): PlanListState = PlanListState(
  isLoading = false,
  agentId = "budget",
  active = listOf(
    PlanListRow(
      planId = "plan-active-1",
      objective = "Составить бюджет на месяц и отслеживать траты по категориям",
      status = PlanStatus.ACTIVE,
      stepsCompleted = 2,
      stepsTotal = 5,
      updatedAt = PREVIEW_FIXED_TIMESTAMP,
    ),
  ),
  paused = listOf(
    PlanListRow(
      planId = "plan-paused-1",
      objective = "Накопить на отпуск",
      status = PlanStatus.PAUSED,
      stepsCompleted = 1,
      stepsTotal = 4,
      updatedAt = PREVIEW_FIXED_TIMESTAMP,
    ),
  ),
  completed = listOf(
    PlanListRow(
      planId = "plan-completed-1",
      objective = "Проверить подписки и отменить лишние",
      status = PlanStatus.COMPLETED,
      stepsCompleted = 3,
      stepsTotal = 3,
      updatedAt = PREVIEW_FIXED_TIMESTAMP,
    ),
  ),
)

@Composable
internal fun PlanListPreviewScaffold(state: PlanListState = samplePlanListState()) {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    PlanListScreen(state = state, onOpenPlan = {}, onNavigateBack = {})
  }
}
