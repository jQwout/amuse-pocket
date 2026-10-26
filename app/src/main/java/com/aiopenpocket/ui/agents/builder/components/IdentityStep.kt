package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.AgentDraft
import com.aiopenpocket.ui.agents.iconFor

/** Every icon key the identity step lets a user pick — mirrors [com.aiopenpocket.ui.agents.iconFor]'s known keys. */
private val ICON_KEYS = listOf("wallet", "translate", "checklist", "alarm", "restaurant", "book", "robot")

/**
 * Step 1/6: name, description, icon and the (read-only) generated id. [id] has no editable control —
 * [com.aiopenpocket.ui.agents.builder.AgentBuilderViewModel] never exposes a way to change it after
 * creation (it seeds [com.aiopenpocket.agent.core.agent.AgentMemoryPolicy.namespace] and the Room
 * primary key), so surfacing it as editable text here would silently do nothing.
 */
@Composable
fun IdentityStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Paddings for the wizard's floating glass islands (their heights are measured in AgentBuilderScreen).
   * Applied AFTER verticalScroll — then the content scrolls UNDER the glass and there is something for
   * it to blur. A viewport inset from outside (through [modifier]) would do the opposite: the canvas does
   * not slide under the islands and the blur samples only the aurora.
   */
  contentPadding: PaddingValues = PaddingValues(),
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = tr("Кто твой агент?", "Who is your agent?"), style = MaterialTheme.typography.headlineSmall)
    Text(
      text = tr(
        "Имя, короткое описание и иконка — то, что пользователь увидит в списке агентов.",
        "Name, short description and icon — what people will see in the agent list.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
    )

    Text(
      text = "id: ${draft.id.ifBlank { tr("— генерируется автоматически", "— generated automatically") }}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 16.dp),
    )

    LabeledField(
      value = draft.name,
      onValueChange = { text -> onUpdateDraft { it.copy(name = text.take(AgentValidator.MAX_AGENT_NAME_CHARS)) } },
      label = tr("Имя агента", "Agent name"),
      maxChars = AgentValidator.MAX_AGENT_NAME_CHARS,
      isError = errors.any { it.field == "name" },
    )
    ErrorText(errors, "name")

    Spacer(modifier = Modifier.height(16.dp))

    LabeledField(
      value = draft.description,
      onValueChange = { text -> onUpdateDraft { it.copy(description = text.take(AgentValidator.MAX_DESCRIPTION_CHARS)) } },
      label = tr("Короткое описание", "Short description"),
      maxChars = AgentValidator.MAX_DESCRIPTION_CHARS,
      isError = errors.any { it.field == "description" },
      minLines = 2,
      maxLines = 4,
    )
    ErrorText(errors, "description")

    Spacer(modifier = Modifier.height(20.dp))

    Text(text = tr("Иконка", "Icon"), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(ICON_KEYS) { key ->
        IconChoice(
          iconKey = key,
          selected = draft.iconKey == key,
          onClick = { onUpdateDraft { it.copy(iconKey = key) } },
        )
      }
    }
    ErrorText(errors, "iconKey")
  }
}

@Composable
private fun LabeledField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  maxChars: Int,
  isError: Boolean,
  minLines: Int = 1,
  maxLines: Int = 1,
) {
  Column {
    BuilderTextField(
      value = value,
      onValueChange = onValueChange,
      label = label,
      isError = isError,
      minLines = minLines,
      maxLines = maxLines,
    )
    Text(
      text = "${value.length} / $maxChars",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
private fun IconChoice(iconKey: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(48.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.35f else 0.10f))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = iconFor(iconKey),
      contentDescription = iconKey,
      tint = MaterialTheme.colorScheme.primary,
    )
  }
}

/** Every issue [errors] has for [field], rendered as small error text under its control. */
@Composable
internal fun ErrorText(errors: List<ValidationIssue>, field: String) {
  errors.filter { it.field == field }.forEach { issue ->
    Text(
      text = issue.message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
