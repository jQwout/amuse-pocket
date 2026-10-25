package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.ui.theme.LocalThemeIsDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.util.formatRelativeDate
import kotlinx.coroutines.flow.Flow

/** Russian label for a whole-plan [PlanStatus] — shared by [PlanCard], the plan list/detail screens, and `AgentDetailScreen`'s Планы section. */
fun planStatusLabel(status: PlanStatus): String = when (status) {
  PlanStatus.DRAFT -> "Черновик"
  PlanStatus.NEEDS_INFO -> "Нужна информация"
  PlanStatus.AWAITING_APPROVAL -> "Ждёт подтверждения"
  PlanStatus.ACTIVE -> "Выполняется"
  PlanStatus.PAUSED -> "На паузе"
  PlanStatus.NEEDS_REVISION -> "Нужна правка"
  PlanStatus.COMPLETED -> "Завершён"
  PlanStatus.CANCELLED -> "Отменён"
  PlanStatus.FAILED -> "Ошибка"
}

/**
 * Accent color for a [PlanStatus] badge — mirrors the risk/enabled badge palette used elsewhere in the agent UI.
 *
 * Цвет идёт в ТЕКСТ (бейджи, строка статуса в карточке), поэтому «активный» — это
 * `colorScheme.primary`, а не прибитый `LimeSignal`: лайм #C4E85C на светлой бумаге не читается,
 * а primary светлой темы — оливковый, тёмной — тот же лайм. Остальные тона по той же причине
 * темнее в светлой теме (см. [statusTone]). Отсюда и @Composable.
 */
@Composable
fun planStatusColor(status: PlanStatus): Color = when (status) {
  PlanStatus.DRAFT, PlanStatus.NEEDS_INFO, PlanStatus.NEEDS_REVISION -> statusTone(StatusGrey)
  PlanStatus.AWAITING_APPROVAL -> statusTone(StatusBlue)
  PlanStatus.ACTIVE -> MaterialTheme.colorScheme.primary
  PlanStatus.PAUSED -> statusTone(StatusOrange)
  PlanStatus.COMPLETED -> statusTone(StatusGreen)
  PlanStatus.CANCELLED -> statusTone(StatusGrey)
  PlanStatus.FAILED -> statusTone(StatusRed)
}

/** Пара тонов статуса: светлый — для тёмной темы, насыщенный тёмный — для светлой бумаги. */
private class StatusPair(val onDark: Color, val onLight: Color)

private val StatusGrey = StatusPair(Color(0xFF9E9E9E), Color(0xFF616161))
private val StatusBlue = StatusPair(Color(0xFF42A5F5), Color(0xFF1565C0))
private val StatusOrange = StatusPair(Color(0xFFFFA726), Color(0xFFE65100))
private val StatusGreen = StatusPair(Color(0xFF4CAF50), Color(0xFF2E7D32))
private val StatusRed = StatusPair(Color(0xFFE53935), Color(0xFFC62828))

/**
 * Тон статуса под тему: светлые #42A5F5 / #FFA726 / #9E9E9E на светлой бумаге дают контраст около
 * 2:1 — текст статуса там просто не читался.
 */
@Composable
private fun statusTone(pair: StatusPair): Color = if (LocalThemeIsDark.current) pair.onDark else pair.onLight

/** Russian label for a per-step [StepStatus] — used by [PlanCard]'s progress branch and the plan detail timeline. */
fun stepStatusLabel(status: StepStatus): String = when (status) {
  StepStatus.PENDING -> "Ожидает"
  StepStatus.READY -> "Готов к запуску"
  StepStatus.EXECUTING -> "Выполняется"
  StepStatus.VERIFYING -> "Проверка"
  StepStatus.BLOCKED -> "Заблокирован"
  StepStatus.COMPLETED -> "Завершён"
  StepStatus.FAILED -> "Ошибка"
  StepStatus.SKIPPED -> "Пропущен"
}

/** Accent color for a [StepStatus] badge — mirrors [planStatusColor]'s palette conventions (и её theme-aware primary). */
@Composable
fun stepStatusColor(status: StepStatus): Color = when (status) {
  StepStatus.PENDING, StepStatus.READY -> statusTone(StatusGrey)
  StepStatus.EXECUTING, StepStatus.VERIFYING -> MaterialTheme.colorScheme.primary
  StepStatus.BLOCKED -> statusTone(StatusOrange)
  StepStatus.COMPLETED, StepStatus.SKIPPED -> statusTone(StatusGreen)
  StepStatus.FAILED -> statusTone(StatusRed)
}

/** Russian label for [PlanHorizon]. */
fun planHorizonLabel(horizon: PlanHorizon): String = when (horizon) {
  PlanHorizon.SINGLE_RUN -> "Разовая задача"
  PlanHorizon.PROGRAM -> "Программа"
}

private val TERMINAL_STEP_STATUSES = setOf(StepStatus.COMPLETED, StepStatus.SKIPPED)

/** The first not-yet-finished step in declared order, or null once every step is COMPLETED/SKIPPED. */
private fun PlanSnapshot.currentStep(): PlanStep? {
  val statusByStepId = stepStates.associateBy { it.stepId }
  return version.steps.firstOrNull { statusByStepId[it.id]?.status !in TERMINAL_STEP_STATUSES }
}

private fun PlanSnapshot.completedStepCount(): Int {
  val statusByStepId = stepStates.associateBy { it.stepId }
  return version.steps.count { statusByStepId[it.id]?.status in TERMINAL_STEP_STATUSES }
}

/** [PlanStep.id] -> its own [com.aiopenpocket.agent.core.plan.model.PlanStepState.nextDueAt], if any. */
private fun PlanSnapshot.nextDueAtFor(stepId: String): Long? = stepStates.firstOrNull { it.stepId == stepId }?.nextDueAt

/**
 * The first BLOCKED step waiting on a still-unproven [VerifierType.USER_CONFIRMATION] criterion, or null.
 * This is exactly the state [com.aiopenpocket.agent.core.plan.persistence.PlanRepository.confirmStep]
 * accepts (a blocked step + at least one pending user-confirmation criterion), so the "Подтвердить шаг"
 * affordance is shown for precisely it and nothing else — an already-confirmed criterion (its evidence
 * exists) or a non-confirmation block never surfaces the button.
 */
private fun PlanSnapshot.stepAwaitingConfirmation(): PlanStep? {
  val hasPendingConfirmation = version.criteria.any { c ->
    c.verifier.type == VerifierType.USER_CONFIRMATION && evidence.none { it.criterionId == c.id }
  }
  if (!hasPendingConfirmation) return null
  val statusByStepId = stepStates.associateBy { it.stepId }
  return version.steps.firstOrNull { statusByStepId[it.id]?.status == StepStatus.BLOCKED }
}

/**
 * Live plan card rendered in the chat stream (Task 12, design spec `2026-07-12-structured-plan-engine-design.md`
 * section 15: "PlanCard вместо plain markdown plan; progress card для текущего run"). Subscribes directly
 * to [observePlan] by [planId] — never trusts a field copied into a chat message — so its content always
 * reflects [com.aiopenpocket.agent.core.plan.persistence.PlanRepository]'s CURRENT state, exactly like
 * `ReminderCardView` subscribes to its own `heartbeatId` (see `DrivenUiEngine.kt`). Unlike a `render_card`
 * template, this card is never something the model can forge: `ChatScreen` only ever mounts it from
 * `ChatUiState.pendingPlanApproval`/the conversation's own ACTIVE/PAUSED plan id — both populated solely
 * from [com.aiopenpocket.agent.core.plan.persistence.PlanRepository] (Task 10), never from LLM output.
 *
 * Two branches, matching [PlanSnapshot.plan]'s [PlanStatus]:
 * - [PlanStatus.AWAITING_APPROVAL]: the proposal preview (objective/steps/criteria) plus
 *   Approve/Request-changes actions ([onApprove]/[onRequestChanges]), reusing Task 5's typed commands —
 *   this card never invents its own approval mechanism.
 * - [PlanStatus.ACTIVE]/[PlanStatus.PAUSED]: compact progress (current step, progress bar, next due date
 *   for a [PlanHorizon.PROGRAM] step) plus Pause/Resume/Cancel.
 *
 * Any other status renders a minimal fallback line — [ChatScreen] only ever resolves a [planId] for one
 * of the two statuses above, but a status can change between this composable's last recomposition and its
 * next collection from [observePlan], so this stays exhaustive rather than assuming.
 */
@Composable
fun PlanCard(
  planId: String,
  observePlan: (String) -> Flow<PlanSnapshot?>,
  onApprove: (planId: String, version: Int) -> Unit,
  onRequestChanges: (planId: String, version: Int, feedback: String) -> Unit,
  onPause: (planId: String) -> Unit,
  onResume: (planId: String) -> Unit,
  onCancel: (planId: String) -> Unit,
  onConfirmStep: (planId: String, stepId: String) -> Unit,
  onOpenDetail: (planId: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val snapshot by remember(planId) { observePlan(planId) }.collectAsState(initial = null)
  val snap = snapshot ?: return

  GlassCard(
    modifier = modifier.fillMaxWidth().testTag("plan_card_$planId"),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = snap.version.objective,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "v${snap.version.version} · ${planHorizonLabel(snap.plan.horizon)} · ${planStatusLabel(snap.plan.status)}",
            style = MaterialTheme.typography.labelSmall,
            color = planStatusColor(snap.plan.status),
          )
        }
        TextButton(
          onClick = { onOpenDetail(planId) },
          modifier = Modifier.testTag("plan_open_detail"),
        ) {
          Text(text = "Открыть →", style = MaterialTheme.typography.labelMedium)
        }
      }
      Spacer(Modifier.height(8.dp))
      when (snap.plan.status) {
        PlanStatus.AWAITING_APPROVAL -> ProposalPreview(snap, onApprove, onRequestChanges)
        PlanStatus.ACTIVE, PlanStatus.PAUSED -> ProgressView(snap, onPause, onResume, onCancel, onConfirmStep)
        else -> Text(
          text = "Статус: ${planStatusLabel(snap.plan.status)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
    }
  }
}

@Composable
private fun ProposalPreview(
  snap: PlanSnapshot,
  onApprove: (planId: String, version: Int) -> Unit,
  onRequestChanges: (planId: String, version: Int, feedback: String) -> Unit,
) {
  var feedback by remember(snap.plan.id, snap.version.version) { mutableStateOf("") }

  val steps = snap.version.steps
  val previewCount = STEP_PREVIEW_LIMIT.coerceAtMost(steps.size)
  Column {
    Text(
      text = "Шаги (${steps.size}):",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
    steps.take(previewCount).forEach { step ->
      Text(
        text = "• ${step.title}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (steps.size > previewCount) {
      Text(
        text = "…и ещё ${steps.size - previewCount}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
    }
    val requiredCriteria = snap.version.criteria.count { it.required }
    if (snap.version.criteria.isNotEmpty()) {
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Критериев: ${snap.version.criteria.size} (обязательных: $requiredCriteria)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
      value = feedback,
      onValueChange = { feedback = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Что поправить? (необязательно)", style = MaterialTheme.typography.bodySmall) },
      textStyle = MaterialTheme.typography.bodySmall,
      singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = { onRequestChanges(snap.plan.id, snap.version.version, feedback) },
        modifier = Modifier.weight(1f).testTag("plan_request_changes"),
      ) {
        Text("Попросить правки")
      }
      Button(
        onClick = { onApprove(snap.plan.id, snap.version.version) },
        modifier = Modifier.weight(1f).testTag("plan_approve"),
      ) {
        Text("Одобрить")
      }
    }
  }
}

@Composable
private fun ProgressView(
  snap: PlanSnapshot,
  onPause: (planId: String) -> Unit,
  onResume: (planId: String) -> Unit,
  onCancel: (planId: String) -> Unit,
  onConfirmStep: (planId: String, stepId: String) -> Unit,
) {
  val total = snap.version.steps.size
  val completed = snap.completedStepCount()
  val current = snap.currentStep()

  Column {
    Text(
      text = if (current != null) "Текущий шаг: ${current.title}" else "Все шаги выполнены",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
      progress = { if (total > 0) completed.toFloat() / total else 0f },
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = "$completed/$total шагов",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    if (snap.plan.horizon == PlanHorizon.PROGRAM && current != null) {
      val dueAt = snap.nextDueAtFor(current.id)
      if (dueAt != null) {
        Text(
          text = "Следующий срок: ${formatRelativeDate(dueAt)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
    }
    // A step BLOCKED on a still-unproven USER_CONFIRMATION criterion is the ONE thing the user can
    // resolve from here without re-running anything: confirming writes the evidence and re-verifies
    // (PlanRepository.confirmStep). Only offered on an ACTIVE plan - a paused one must be resumed first.
    val awaitingConfirmation = if (snap.plan.status == PlanStatus.ACTIVE) snap.stepAwaitingConfirmation() else null
    if (awaitingConfirmation != null) {
      Spacer(Modifier.height(8.dp))
      Text(
        text = "Шаг ждёт твоего подтверждения: ${awaitingConfirmation.title}",
        style = MaterialTheme.typography.bodySmall,
        color = stepStatusColor(StepStatus.BLOCKED),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Button(
        onClick = { onConfirmStep(snap.plan.id, awaitingConfirmation.id) },
        modifier = Modifier.fillMaxWidth().testTag("plan_confirm_step"),
      ) {
        Text("Подтвердить шаг")
      }
    }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (snap.plan.status == PlanStatus.ACTIVE) {
        OutlinedButton(
          onClick = { onPause(snap.plan.id) },
          modifier = Modifier.weight(1f).testTag("plan_pause"),
        ) {
          Text("Пауза")
        }
      } else {
        OutlinedButton(
          onClick = { onResume(snap.plan.id) },
          modifier = Modifier.weight(1f).testTag("plan_resume"),
        ) {
          Text("Продолжить")
        }
      }
      OutlinedButton(
        onClick = { onCancel(snap.plan.id) },
        modifier = Modifier.weight(1f).testTag("plan_cancel"),
      ) {
        Text("Отменить")
      }
    }
  }
}

private const val STEP_PREVIEW_LIMIT = 4
