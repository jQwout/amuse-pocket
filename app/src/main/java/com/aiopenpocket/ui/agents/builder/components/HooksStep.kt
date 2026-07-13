package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.ui.agents.builder.AgentDraft
import com.aiopenpocket.ui.components.glass.glassCardSurface

/**
 * Step 5/6: declarative lifecycle hooks. Adding/editing goes through [HookEditorDialog] — this step
 * itself only lists [AgentDraft.hooks] and routes add/edit/delete/enable-toggle into [onUpdateDraft];
 * it never touches [AgentHook.conditionJson]/[AgentHook.actionJson] directly.
 */
@Composable
fun HooksStep(
  draft: AgentDraft,
  errors: List<ValidationIssue>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showEditor by remember { mutableStateOf(false) }
  var editingHook by remember { mutableStateOf<AgentHook?>(null) }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Text(text = "Хуки", style = MaterialTheme.typography.headlineSmall)
    Text(
      text = "Правила на события хода: добавить инструкцию, потребовать подтверждение, " +
        "запретить действие, записать в лог или остановить ход.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )
    ErrorText(errors, "hooks")

    if (draft.hooks.isEmpty()) {
      Text(
        text = "Хуков пока нет.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      draft.hooks.sortedWith(compareBy({ it.priority }, { it.id })).forEach { hook ->
        HookRow(
          hook = hook,
          onClick = {
            editingHook = hook
            showEditor = true
          },
          onToggleEnabled = { enabled ->
            onUpdateDraft { d -> d.copy(hooks = d.hooks.map { if (it.id == hook.id) it.copy(enabled = enabled) else it }) }
          },
          onDelete = {
            onUpdateDraft { d -> d.copy(hooks = d.hooks.filterNot { it.id == hook.id }) }
          },
        )
        Spacer(modifier = Modifier.height(8.dp))
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Button(
      onClick = {
        editingHook = null
        showEditor = true
      },
      modifier = Modifier.testTag("agent_builder_hook_add"),
    ) { Text("Добавить хук") }
  }

  if (showEditor) {
    HookEditorDialog(
      agentId = draft.id,
      existing = editingHook,
      agentToolNames = draft.toolNames,
      onSave = { hook ->
        onUpdateDraft { d ->
          val replaced = d.hooks.any { it.id == hook.id }
          d.copy(hooks = if (replaced) d.hooks.map { if (it.id == hook.id) hook else it } else d.hooks + hook)
        }
        showEditor = false
        editingHook = null
      },
      onDismiss = {
        showEditor = false
        editingHook = null
      },
    )
  }
}

@Composable
private fun HookRow(hook: AgentHook, onClick: () -> Unit, onToggleEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface()
      .clickable(onClick = onClick)
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "${hookEventLabel(hook.event)} → ${hookActionLabel(hook.actionType)}",
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = "Условие: ${hookConditionSummary(hook)} · приоритет ${hook.priority}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = hook.enabled, onCheckedChange = onToggleEnabled)
    Spacer(modifier = Modifier.width(4.dp))
    IconButton(onClick = onDelete) {
      Icon(imageVector = Icons.Default.Delete, contentDescription = "Удалить хук")
    }
  }
}
