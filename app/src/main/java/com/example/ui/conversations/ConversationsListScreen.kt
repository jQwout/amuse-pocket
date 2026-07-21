package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.util.formatDateTime
import dev.zacsweers.metrox.viewmodel.metroViewModel
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

  // Удаление — через подтверждение: клик по корзине лишь намечает диалог к удалению, а сносит его
  // явное «Удалить» в диалоге. Иначе один промах по корзине безвозвратно сносил чат.
  var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

  // Аврора-фон + стекло, как на вкладке «Чат».
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = "Чаты",
    glassState = glassState,
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
        if (state.visibleConversations.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              "Нет чатов с тегом «${state.selectedTag}».",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.visibleConversations, key = { it.id }) { conversation ->
              ConversationItem(
                conversation = conversation,
                turnStatus = state.turnStatuses[conversation.id],
                onClick = { onConversationClick(conversation.id) },
                onToggleFavorite = { viewModel.toggleFavorite(conversation.id, conversation.isFavorite) },
                onDelete = { pendingDelete = conversation },
              )
            }
          }
        }
      }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("Удалить чат?") },
        text = { Text("«${toDelete.title}» и вся переписка будут удалены без возможности восстановления.") },
        confirmButton = {
          TextButton(onClick = {
            viewModel.deleteConversation(toDelete.id)
            pendingDelete = null
          }) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { pendingDelete = null }) {
            Text("Отмена")
          }
        },
      )
    }
  }
}

@Composable
private fun ConversationItem(
  conversation: Conversation,
  turnStatus: TurnStatus?,
  onClick: () -> Unit,
  onToggleFavorite: () -> Unit,
  onDelete: () -> Unit,
) {
  GlassCard(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    onClick = onClick,
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
      // Индикация фонового хода: спиннер — агент думает, точка — готово (tertiary) или ошибка (error).
      when (turnStatus) {
        is TurnStatus.Running -> CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          strokeWidth = 2.dp,
        )

        is TurnStatus.Completed, is TurnStatus.Failed -> Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
              if (turnStatus is TurnStatus.Failed || (turnStatus is TurnStatus.Completed && turnStatus.error != null)) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.tertiary
              },
            ),
        )

        null -> Unit
      }
      IconButton(onClick = onToggleFavorite) {
        Icon(
          imageVector = if (conversation.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
          contentDescription = if (conversation.isFavorite) "Убрать из избранного" else "В избранное",
          tint = if (conversation.isFavorite) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = "Удалить")
      }
    }
  }
}
