package com.aiopenpocket.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsListScreen(
  onConversationClick: (String) -> Unit,
  onNavigateBack: () -> Unit,
  onNavigateToGraph: () -> Unit,
) {
  val viewModel: ConversationsListViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Чаты") },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Назад",
            )
          }
        },
        actions = {
          IconButton(onClick = onNavigateToGraph) {
            Icon(Icons.Default.Share, contentDescription = "Граф памяти")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = {
        scope.launch {
          val id = viewModel.createConversation()
          if (id != null) {
            onConversationClick(id)
          }
        }
      }) {
        Icon(Icons.Default.Add, contentDescription = "Новый чат")
      }
    },
  ) { padding ->
    if (state.conversations.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Нет чатов. Нажмите + чтобы создать.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Column(modifier = Modifier.padding(padding)) {
        if (state.allTags.isNotEmpty()) {
          LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(state.allTags, key = { it }) { tag ->
              FilterChip(
                selected = state.selectedTag == tag,
                onClick = { viewModel.toggleTagFilter(tag) },
                label = { Text(tag) },
              )
            }
          }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          items(state.visibleConversations, key = { it.id }) { conversation ->
            ConversationItem(
              conversation = conversation,
              onClick = { onConversationClick(conversation.id) },
              onDelete = { viewModel.deleteConversation(conversation.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ConversationItem(
  conversation: com.aiopenpocket.agent.core.memory.model.Conversation,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp)
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          conversation.title,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          formatDate(conversation.updatedAt),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (conversation.tags.isNotEmpty()) {
          Text(
            conversation.tags.joinToString(" · ") { "#$it" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = "Удалить")
      }
    }
  }
}

private fun formatDate(timestamp: Long): String {
  val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
  return sdf.format(Date(timestamp))
}
