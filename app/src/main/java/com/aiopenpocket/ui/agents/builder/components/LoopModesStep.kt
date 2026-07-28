package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.ui.agents.builder.AgentDraft

/**
 * Every [AgentMode] a custom agent can be started in from the wizard. [AgentMode.TUTOR] is
 * deliberately excluded — it is a lesson-internal, code-driven mode (mirrors
 * [com.aiopenpocket.agent.core.agent.MiniAgent.toDefinition]'s own preset scope), never something a
 * user picks for their own agent.
 */
private val SELECTABLE_MODES = listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT)

/** Step 4/6: which loop modes this agent can run in, and which one a new chat with it opens in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoopModesStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Отступы под плавающие стеклянные острова визарда (высоты меряются в AgentBuilderScreen).
   * Применяются ПОСЛЕ verticalScroll — тогда контент прокручивается ПОД стеклом и его есть
   * чему размывать. Инсет вьюпорта снаружи (через [modifier]) дал бы обратное: полотно под
   * острова не заезжает и блюр сэмплит одну аврору.
   */
  contentPadding: PaddingValues = PaddingValues(),
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = "Режимы работы", style = MaterialTheme.typography.headlineSmall)
    Text(
      text = "В каких режимах агент может отвечать, и какой запускается по умолчанию.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
    )

    Text(text = "Разрешённые режимы", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SELECTABLE_MODES.forEach { mode ->
        val selected = mode in draft.allowedModes
        FilterChip(
          selected = selected,
          onClick = {
            onUpdateDraft { d ->
              val updated = if (selected) d.allowedModes - mode else d.allowedModes + mode
              // Никогда не оставляем allowedModes пустым — иначе нечего выбрать в качестве default.
              if (updated.isEmpty()) d else d.copy(allowedModes = updated)
            }
          },
          label = { Text(modeLabel(mode)) },
        )
      }
    }
    // Нет ErrorText("allowedModes") здесь: AgentValidator не производит issue с таким field —
    // единственное правило про режимы (default_mode_not_allowed) размечено как "defaultMode",
    // что и показываем ниже.

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = "Режим по умолчанию", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SELECTABLE_MODES.forEach { mode ->
        FilterChip(
          selected = draft.defaultMode == mode,
          onClick = {
            // Выбор default всегда держит его входящим в allowedModes — иначе тут же ловим
            // AgentValidator.default_mode_not_allowed на ровном месте.
            onUpdateDraft { it.copy(defaultMode = mode, allowedModes = it.allowedModes + mode) }
          },
          label = { Text(modeLabel(mode)) },
        )
      }
    }
    ErrorText(errors, "defaultMode")

    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = "Режим по умолчанию — тот, в котором открывается новый чат с этим агентом.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun modeLabel(mode: AgentMode): String = when (mode) {
  AgentMode.CHAT -> "Чат"
  AgentMode.PLAN -> "План"
  AgentMode.GOAL -> "Цель"
  AgentMode.REFLECT -> "Рефлексия"
  AgentMode.TUTOR -> "Репетитор"
}
