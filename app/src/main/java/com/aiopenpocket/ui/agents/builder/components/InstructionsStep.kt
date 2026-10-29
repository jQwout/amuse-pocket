package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.InstructionsGuide
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.AgentDraft

/**
 * Step 2/6: the agent's system prompt — its personality and rules of engagement, injected the same
 * way [com.aiopenpocket.agent.core.agent.MiniAgent.systemPrompt] is for presets.
 */
@Composable
fun InstructionsStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = tr("Инструкции", "Instructions"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr(
        "Опиши личность и правила поведения агента — это его системный промпт. " +
          "Пиши от первого лица агента, конкретно: что он делает, а что — нет.",
        "Describe the agent's personality and rules of behavior — this is its system prompt. " +
          "Address the agent directly and be specific: what it does and what it doesn't.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    BuilderTextField(
      value = draft.systemPrompt,
      onValueChange = { text ->
        onUpdateDraft { it.copy(systemPrompt = text.take(AgentValidator.MAX_SYSTEM_PROMPT_CHARS)) }
      },
      placeholder = tr(
        "Например: Ты — тренер по бегу. Составляй план тренировок на неделю. " +
          "Отвечай списком, не длиннее 6 предложений. Тон — дружелюбный и ободряющий.",
        "For example: You are a running coach. Build a weekly training plan. " +
          "Reply as a list, no longer than 6 sentences. Keep the tone friendly and encouraging.",
      ),
      isError = errors.any { it.field == "systemPrompt" },
      minLines = 8,
      maxLines = 14,
      modifier = Modifier.weight(1f, fill = false),
    )
    Text(
      text = "${draft.systemPrompt.length} / ${AgentValidator.MAX_SYSTEM_PROMPT_CHARS}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )
    ErrorText(errors, "systemPrompt")

    // Advice only (Meta's prompting guide): never gates saving or publishing.
    val coverage = InstructionsGuide.analyze(draft.systemPrompt)
    Column(modifier = Modifier.padding(top = 10.dp)) {
      ChecklistItem(tr("Роль или задача", "Role / task"), coverage.role)
      ChecklistItem(tr("Формат ответа", "Output format"), coverage.format)
      ChecklistItem(tr("Длина", "Length"), coverage.length)
      ChecklistItem(tr("Тон", "Tone"), coverage.tone)
      if (coverage.metaInstruction) {
        Text(
          text = tr(
            "Meta советует описывать задачу, а не то, как модели думать.",
            "Meta recommends describing the task, not how the model should think.",
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun ChecklistItem(label: String, covered: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = if (covered) "\u2713" else "\u25CB",
      style = MaterialTheme.typography.bodySmall,
      color = if (covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 8.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = if (covered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
