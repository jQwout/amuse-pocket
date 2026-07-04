package com.aiopenpocket.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.MiniAgent
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AgentsScreen(
  onOpenChat: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: AgentsViewModel = metroViewModel()

  Scaffold { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          IconButton(onClick = onNavigateBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Назад",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = "AI Academy",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          )
        }
      }

      item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = "Агенты",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Специалисты со своими инструментами. Координатор сам распределяет задачу между ними.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(24.dp))
      }

      items(viewModel.agents, key = { it.id }) { agent ->
        AgentCard(agent = agent, onClick = { viewModel.openAgentChat(agent.id, onOpenChat) })
        Spacer(modifier = Modifier.height(12.dp))
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun AgentCard(agent: MiniAgent, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccent.copy(alpha = 0.18f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = iconFor(agent.iconKey),
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = if (agent.isSupervisor) "${agent.name} · координатор" else agent.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = agent.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
  }
}

private fun iconFor(key: String): ImageVector = when (key) {
  "calendar" -> Icons.Default.CalendarMonth
  "folder" -> Icons.Default.Folder
  "message" -> Icons.Default.Email
  "chart" -> Icons.Default.BarChart
  "tree" -> Icons.Default.AccountTree
  "translate" -> Icons.Default.Translate
  "hub" -> Icons.Default.Hub
  else -> Icons.Default.SmartToy
}
