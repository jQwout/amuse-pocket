package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.mcp.RiskClass

/**
 * Consent gate for the CAPABILITIES step (Task 20 plan Step 4): shown the first time a tool whose
 * [ToolCatalogEntry.requiresConsent] is true transitions from unselected to selected. Declining
 * ([onDismiss]) must leave the tool OUT of [com.aiopenpocket.ui.agents.builder.AgentDraft.toolNames] —
 * enforced by the CALLER never adding the tool until [onConfirm] fires, this dialog only presents the
 * choice.
 */
@Composable
fun RiskConsentDialog(
  tool: ToolCatalogEntry,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Включить «${tool.label}»?") },
    text = {
      Column {
        Text(
          text = riskClassLabel(tool.riskClass),
          style = MaterialTheme.typography.labelLarge,
          color = riskColor(tool.riskClass),
        )
        Text(
          text = tool.consequence,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
        tool.permission?.let { permission ->
          Text(
            text = "Разрешение Android: $permission",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text("Включить") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Отмена") }
    },
  )
}

/** Badge tint per [RiskClass] — reused by every tool row and this dialog. */
@Composable
fun riskColor(risk: RiskClass) = when (risk) {
  RiskClass.READ -> MaterialTheme.colorScheme.onSurfaceVariant
  RiskClass.WRITE -> MaterialTheme.colorScheme.tertiary
  RiskClass.DESTRUCTIVE -> MaterialTheme.colorScheme.error
  RiskClass.EXTERNAL -> MaterialTheme.colorScheme.error
}
