package com.aiopenpocket.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.ui.chat.components.ToolTracePanel
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  conversationId: String,
  onNavigateBack: () -> Unit,
  onOpenQuiz: (String) -> Unit = {},
) {
  val viewModel: ChatViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(conversationId) {
    viewModel.setConversationId(conversationId)
  }

  ChatContent(
    state = state,
    onNavigateBack = onNavigateBack,
    onInputChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
    onSend = { viewModel.handleIntent(ChatIntent.SendMessage) },
    onCancel = { viewModel.handleIntent(ChatIntent.CancelRequest) },
    onToggleTrace = { viewModel.handleIntent(ChatIntent.ToggleToolTrace) },
    onTogglePin = { viewModel.handleIntent(ChatIntent.TogglePin(it)) },
    onSendMessage = viewModel::sendUserMessage,
    onLessonComplete = {
      state.levelId?.let(onOpenQuiz) ?: onNavigateBack()
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
  state: ChatUiState,
  onNavigateBack: () -> Unit,
  onInputChange: (String) -> Unit,
  onSend: () -> Unit,
  onCancel: () -> Unit,
  onToggleTrace: () -> Unit,
  onTogglePin: (String) -> Unit,
  onSendMessage: (String) -> Unit,
  onLessonComplete: () -> Unit,
) {
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
            IconButton(onClick = onToggleTrace) {
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
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      val listState = rememberLazyListState()
      val hasStreamingItem = state.isLoading
      LaunchedEffect(state.messages.size, state.streamingText, hasStreamingItem) {
        val lastIndex = state.messages.size + (if (hasStreamingItem) 1 else 0) - 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
      }

      val pinned = state.messages.filter { it.isPinned }
      if (pinned.isNotEmpty()) PinnedBar(pinned = pinned, onUnpin = onTogglePin)

      LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
        items(state.messages, key = { it.id }) { message ->
          MessageBubble(
            message = message,
            onTogglePin = { onTogglePin(message.id) },
            interactiveCards = state.isTutorial,
            onChoiceSelected = onSendMessage,
            onFormSubmitted = onSendMessage,
            onLessonComplete = onLessonComplete,
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
        ToolTracePanel(traces = state.toolTrace, modifier = Modifier.padding(8.dp))
      }

      state.error?.let {
        Text(
          text = it,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }

      ChatInput(
        value = state.inputText,
        onValueChange = onInputChange,
        onSend = onSend,
        onCancel = onCancel,
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
  interactiveCards: Boolean = false,
  onChoiceSelected: (String) -> Unit = {},
  onFormSubmitted: (String) -> Unit = {},
  onLessonComplete: () -> Unit = {},
) {
  val isUser = message.role == "user"
  val hasCard = !message.cardSpec.isNullOrBlank()
  val interactive = interactiveCards && hasCard

  val cardMarkdown = message.cardSpec
    ?.takeIf { it.isNotBlank() && !interactive }
    ?.let { DrivenUiEngine.parseJsonString(it).toMarkdown() }
  val displayText = when {
    cardMarkdown != null && message.content.isNotBlank() -> "${message.content}\n\n$cardMarkdown"
    cardMarkdown != null -> cardMarkdown
    else -> message.content
  }

  Box(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    Column(
      modifier = Modifier
        .clip(RoundedCornerShape(16.dp))
        .background(
          if (isUser) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        )
        .combinedClickable(onClick = {}, onLongClick = onTogglePin)
        .padding(12.dp),
    ) {
      if (message.isPinned) {
        PinnedTag()
        Spacer(modifier = Modifier.height(4.dp))
      }
      if (interactive) {
        if (message.content.isNotBlank()) {
          MarkdownText(text = message.content)
          Spacer(modifier = Modifier.height(8.dp))
        }
        RenderDrivenUiCard(
          spec = message.cardSpec!!,
          onChoiceSelected = onChoiceSelected,
          onFormSubmitted = onFormSubmitted,
          onLessonComplete = onLessonComplete,
        )
      } else {
        MarkdownText(text = displayText)
      }
    }
  }
}

@Composable
private fun PinnedTag() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      Icons.Default.PushPin,
      contentDescription = "Закреплено",
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(14.dp),
    )
    Text(
      "Закреплено",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun PinnedBar(pinned: List<Message>, onUnpin: (String) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        Icons.Default.PushPin,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(16.dp),
      )
      Text(
        "Закреплённые (${pinned.size})",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    pinned.forEach { msg ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          text = pinnedSnippet(msg),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onUnpin(msg.id) }, modifier = Modifier.size(28.dp)) {
          Icon(
            Icons.Default.Close,
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
    modifier = Modifier.fillMaxWidth().padding(8.dp),
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ChatScreenPreview() {
  MyApplicationTheme {
    ChatContent(
      state = ChatUiState(
        conversationId = "demo",
        messages = listOf(
          Message(
            id = "1",
            conversationId = "demo",
            role = "user",
            content = "Привет! Что ты умеешь?",
            toolCallsJson = null,
            createdAt = 0L,
          ),
          Message(
            id = "2",
            conversationId = "demo",
            role = "assistant",
            content = "Могу вести заметки, искать в памяти, ставить напоминания и собирать карточки.",
            toolCallsJson = null,
            isPinned = true,
            createdAt = 0L,
          ),
        ),
        inputText = "",
        isLoading = false,
      ),
      onNavigateBack = {},
      onInputChange = {},
      onSend = {},
      onCancel = {},
      onToggleTrace = {},
      onTogglePin = {},
      onSendMessage = {},
      onLessonComplete = {},
    )
  }
}
