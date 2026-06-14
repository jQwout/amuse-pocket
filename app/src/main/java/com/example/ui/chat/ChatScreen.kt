package com.aiopenpocket.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Message
import dev.zacsweers.metrox.viewmodel.metroViewModel
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.chat.components.ToolTracePanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  conversationId: String,
  onNavigateBack: () -> Unit,
) {
  val viewModel: ChatViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(conversationId) {
    viewModel.setConversationId(conversationId)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Чат") },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Назад")
          }
        },
        actions = {
          if (state.toolTrace.isNotEmpty()) {
            IconButton(onClick = { viewModel.handleIntent(ChatIntent.ToggleToolTrace) }) {
              Icon(Icons.Default.Build, contentDescription = "Tool trace")
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      val listState = rememberLazyListState()
      val hasStreamingItem = state.isLoading
      LaunchedEffect(state.messages.size, state.streamingText, hasStreamingItem) {
        val lastIndex = state.messages.size + (if (hasStreamingItem) 1 else 0) - 1
        if (lastIndex >= 0) {
          listState.animateScrollToItem(lastIndex)
        }
      }

      val pinned = state.messages.filter { it.isPinned }
      if (pinned.isNotEmpty()) {
        PinnedBar(
          pinned = pinned,
          onUnpin = { viewModel.handleIntent(ChatIntent.TogglePin(it)) },
        )
      }

      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
      ) {
        items(state.messages, key = { it.id }) { message ->
          MessageBubble(
            message = message,
            onTogglePin = { viewModel.handleIntent(ChatIntent.TogglePin(message.id)) },
          )
        }
        if (hasStreamingItem) {
          item(key = "streaming") {
            StreamingBubble(
              text = state.streamingText,
              showCursor = state.streamingText.isNotEmpty(),
              isWaiting = state.streamingText.isEmpty(),
            )
          }
        }
      }

      if (state.showToolTrace && state.toolTrace.isNotEmpty()) {
        ToolTracePanel(
          traces = state.toolTrace,
          modifier = Modifier.padding(8.dp),
        )
      }

      if (state.error != null) {
        Text(
          text = state.error!!,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }

      ChatInput(
        value = state.inputText,
        onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
        onSend = { viewModel.handleIntent(ChatIntent.SendMessage) },
        onCancel = { viewModel.handleIntent(ChatIntent.CancelRequest) },
        isLoading = state.isLoading,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
  message: Message,
  onTogglePin: () -> Unit,
) {
  val isUser = message.role == "user"

  // Markdown-only mode: render_card specs are shown as markdown, not interactive widgets.
  val cardMarkdown = message.cardSpec
    ?.takeIf { it.isNotBlank() }
    ?.let { DrivenUiEngine.parseJsonString(it).toMarkdown() }
  val displayText = when {
    cardMarkdown != null && message.content.isNotBlank() -> "${message.content}\n\n$cardMarkdown"
    cardMarkdown != null -> cardMarkdown
    else -> message.content
  }

  val backgroundColor = if (isUser) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    contentAlignment = alignment,
  ) {
    Column(
      modifier = Modifier
        .clip(RoundedCornerShape(16.dp))
        .background(backgroundColor)
        // Long-press a message to pin / unpin it.
        .combinedClickable(onClick = {}, onLongClick = onTogglePin)
        .padding(12.dp),
    ) {
      if (message.isPinned) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.PushPin,
            contentDescription = "Закреплено",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
          )
          Spacer(modifier = Modifier.size(4.dp))
          Text(
            text = "Закреплено",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
      }
      MarkdownText(text = displayText)
    }
  }
}

@Composable
private fun PinnedBar(
  pinned: List<Message>,
  onUnpin: (String) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Default.PushPin,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.size(6.dp))
      Text(
        text = "Закреплённые (${pinned.size})",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    pinned.forEach { msg ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = pinnedSnippet(msg),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = { onUnpin(msg.id) },
          modifier = Modifier.size(28.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Открепить",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

private fun pinnedSnippet(message: Message): String {
  val base = message.content.ifBlank { if (message.cardSpec != null) "📋 Карточка" else "" }
  val firstLine = base.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
  return if (message.role == "user") "Вы: $firstLine" else firstLine
}

@Composable
private fun ChatInput(
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onCancel: () -> Unit,
  isLoading: Boolean,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      placeholder = { Text("Сообщение...") },
      singleLine = false,
      maxLines = 4,
    )
    if (isLoading) {
      // Прогресс под кнопкой «стоп» — даёт пользователю возможность отменить ход.
      Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        IconButton(onClick = onCancel) {
          Icon(Icons.Default.Stop, contentDescription = "Остановить")
        }
      }
    } else {
      IconButton(onClick = onSend) {
        Icon(Icons.AutoMirrored.Default.Send, contentDescription = "Отправить")
      }
    }
  }
}
