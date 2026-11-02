package com.aiopenpocket.ui.chat.components

// @trace agent-router-dc2233
//
// Who answers in a chat bound to no agent: the chip next to the mode chips (the router, the plain assistant
// or one of the person's agents) and the agent's name over an answer the router handed to it.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.RouteChoice
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.RouteAgent

/** The picker's caption for [choice]: what the chip says when closed. */
internal fun routeChoiceLabel(choice: RouteChoice, agents: List<RouteAgent>): String = when (choice) {
  RouteChoice.Auto -> tr("Агент: авто", "Agent: auto")
  RouteChoice.None -> tr("Без агента", "No agent")
  is RouteChoice.Fixed -> agents.firstOrNull { it.id == choice.agentId }?.name ?: tr("Агент: авто", "Agent: auto")
}

@Composable
internal fun RoutePickerChip(
  choice: RouteChoice,
  agents: List<RouteAgent>,
  enabled: Boolean,
  onSelect: (RouteChoice) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    AssistChip(
      onClick = { open = true },
      enabled = enabled,
      label = { Text(routeChoiceLabel(choice, agents), maxLines = 1, overflow = TextOverflow.Ellipsis) },
      leadingIcon = { Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp)) },
      trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
      modifier = Modifier.testTag("chat_route_picker"),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = {
          Text(tr("Авто — Muse Spark выберет агента", "Auto — Muse Spark picks the agent"))
        },
        onClick = { open = false; onSelect(RouteChoice.Auto) },
      )
      DropdownMenuItem(
        text = { Text(tr("Без агента", "No agent")) },
        onClick = { open = false; onSelect(RouteChoice.None) },
      )
      agents.forEach { agent ->
        DropdownMenuItem(
          text = { Text(agent.name) },
          onClick = { open = false; onSelect(RouteChoice.Fixed(agent.id)) },
        )
      }
    }
  }
}

/** "→ Reminders" over an answer the router handed to an agent, so the person sees who answered. */
@Composable
internal fun RoutedAgentLabel(name: String, modifier: Modifier = Modifier) {
  Text(
    text = "→ $name",
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier
      .padding(start = 16.dp, top = 4.dp)
      .testTag("chat_routed_agent"),
  )
}
