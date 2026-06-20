package com.aiopenpocket.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Message
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPageScreen(
  conversationId: String,
  onNavigateBack: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  val viewModel: MemoryPageViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(conversationId) {
    viewModel.load(conversationId)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(state.title.ifBlank { "Память" }) },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
          }
        },
        actions = {
          IconButton(onClick = { onOpenChat(conversationId) }) {
            Icon(Icons.Default.Edit, contentDescription = "Открыть чат")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.summary.isNullOrBlank()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text("Краткое содержание", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(state.summary!!, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }

      if (state.tags.isNotEmpty()) {
        item {
          Text(
            state.tags.joinToString(" · ") { "#$it" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      item {
        Text("Сообщения", style = MaterialTheme.typography.titleSmall)
      }

      items(state.messages, key = { it.id }) { message ->
        MessageRow(message)
      }
    }
  }
}

@Composable
private fun MessageRow(message: Message) {
  val isUser = message.role == "user"
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Column {
      Text(
        if (isUser) "Вы" else "Агент",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
      )
      Text(message.content, style = MaterialTheme.typography.bodyMedium)
    }
  }
}
