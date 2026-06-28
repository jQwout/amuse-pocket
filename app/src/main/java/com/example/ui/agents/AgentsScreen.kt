package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3

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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.MiniAgent
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AgentsScreen(
  onOpenChat: (String) -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: AgentsViewModel = metroViewModel()

  AcademyCollapsingScaffold(
    title = "Агенты",
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Назад",
        )
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Агенты-специалисты со своими инструментами. Открой нужного — и опиши задачу.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(20.dp))
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
        text = agent.name,
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
  "wallet" -> Icons.Default.AccountBalanceWallet
  "translate" -> Icons.Default.Translate
  "checklist" -> Icons.Default.Checklist
  "alarm" -> Icons.Default.Alarm
  "restaurant" -> Icons.Default.Restaurant
  "book" -> Icons.Default.MenuBook
  else -> Icons.Default.SmartToy
}
