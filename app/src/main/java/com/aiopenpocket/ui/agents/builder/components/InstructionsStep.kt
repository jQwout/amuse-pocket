package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentValidator
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
        "Например: Ты — заботливый ассистент по бегу. Планируешь тренировки, следишь за самочувствием...",
        "For example: You are a caring running coach. You plan workouts and keep an eye on how I feel...",
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
  }
}
