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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.util.formatDateTime
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsListScreen(
  onConversationClick: (String) -> Unit,
  onNavigateToSettings: () -> Unit,
  onNavigateToRoadmap: () -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToGraph: () -> Unit,
) {
  val viewModel: ConversationsListViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  val scope = rememberCoroutineScope()

  ConversationsListContent(
    state = state,
    onConversationClick = onConversationClick,
    onNavigateToSettings = onNavigateToSettings,
    onNavigateToRoadmap = onNavigateToRoadmap,
    onNavigateToLibrary = onNavigateToLibrary,
    onNavigateToGraph = onNavigateToGraph,
    onCreate = {
      scope.launch {
        viewModel.createConversation()?.let(onConversationClick)
      }
    },
    onToggleTag = viewModel::toggleTagFilter,
    onDelete = viewModel::deleteConversation,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsListContent(
  state: ConversationsListState,
  onConversationClick: (String) -> Unit,
  onNavigateToSettings: () -> Unit,
  onNavigateToRoadmap: () -> Unit,
  onNavigateToLibrary: () -> Unit,
  onNavigateToGraph: () -> Unit,
  onCreate: () -> Unit,
  onToggleTag: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Чаты") },
        actions = {
          IconButton(onClick = onNavigateToGraph) {
            Icon(Icons.Default.Share, contentDescription = "Граф памяти")
          }
          IconButton(onClick = onNavigateToSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Настройки")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = onCreate) {
        Icon(Icons.Default.Add, contentDescription = "Новый чат")
      }
    },
    bottomBar = {
      AcademyBottomNav(
        selectedTab = AcademyTab.Chats,
        onTabSelected = { tab ->
          when (tab) {
            AcademyTab.Chats -> Unit
            AcademyTab.Path -> onNavigateToRoadmap()
            AcademyTab.Library -> onNavigateToLibrary()
          }
        },
      )
    },
  ) { padding ->
    if (state.conversations.isEmpty()) {
      EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
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
                onClick = { onToggleTag(tag) },
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
              onDelete = { onDelete(conversation.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyState(modifier: Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Text(
      "Нет чатов. Нажмите + чтобы создать.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ConversationItem(
  conversation: Conversation,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp)
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(conversation.title, style = MaterialTheme.typography.titleMedium)
        Text(
          formatDateTime(conversation.updatedAt),
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ConversationsListPreview() {
  MyApplicationTheme {
    ConversationsListContent(
      state = ConversationsListState(
        conversations = listOf(
          Conversation(
            id = "1",
            title = "Идеи проекта",
            createdAt = 0L,
            updatedAt = 0L,
            personaId = "sharp_partner",
            summary = null,
            tags = listOf("работа", "идеи"),
          ),
          Conversation(
            id = "2",
            title = "Покупки на выходные",
            createdAt = 0L,
            updatedAt = 0L,
            personaId = "sharp_partner",
            summary = null,
            tags = listOf("быт"),
          ),
        ),
      ),
      onConversationClick = {},
      onNavigateToSettings = {},
      onNavigateToRoadmap = {},
      onNavigateToLibrary = {},
      onNavigateToGraph = {},
      onCreate = {},
      onToggleTag = {},
      onDelete = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ConversationsListEmptyPreview() {
  MyApplicationTheme {
    ConversationsListContent(
      state = ConversationsListState(),
      onConversationClick = {},
      onNavigateToSettings = {},
      onNavigateToRoadmap = {},
      onNavigateToLibrary = {},
      onNavigateToGraph = {},
      onCreate = {},
      onToggleTag = {},
      onDelete = {},
    )
  }
}
