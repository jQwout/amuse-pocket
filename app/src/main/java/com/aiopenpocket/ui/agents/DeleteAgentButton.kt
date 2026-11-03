package com.aiopenpocket.ui.agents

// @trace user-agent-builder-848153

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr

/**
 * "Delete agent", red and on its own, with a confirmation. Under the hood it archives the agent
 * (`UserAgentRepository.archive`): the row stays so its chats keep their history, but nothing in the app
 * shows it again and there is no way back, so to the person it is a delete. It used to be an "Archive"
 * chip among Duplicate/Edit/Disable, with no confirmation, which the owner could not find (3 Nov 2026).
 */
@Composable
fun DeleteAgentButton(
  name: String,
  isDraft: Boolean,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirming by remember { mutableStateOf(false) }
  val error = MaterialTheme.colorScheme.error
  OutlinedButton(
    onClick = { confirming = true },
    border = BorderStroke(1.dp, error),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
    modifier = modifier.fillMaxWidth().testTag("agent_delete"),
  ) {
    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(if (isDraft) tr("Удалить черновик", "Delete draft") else tr("Удалить агента", "Delete agent"))
  }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text(tr("Удалить «$name»?", "Delete “$name”?")) },
      text = {
        Text(
          tr(
            "Агент пропадёт из списка, и вернуть его будет нельзя. Чаты с ним останутся в списке чатов.",
            "The agent disappears from the list and can't be brought back. Your chats with it stay in the chat list.",
          ),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirming = false
            onConfirm()
          },
          colors = ButtonDefaults.textButtonColors(contentColor = error),
          modifier = Modifier.testTag("agent_delete_confirm"),
        ) { Text(tr("Удалить", "Delete")) }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text(tr("Отмена", "Cancel")) } },
    )
  }
}
