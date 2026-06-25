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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.aiopenpocket.agent.loop.AgentMode
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
  onOpenQuiz: (String) -> Unit = {},
) {
  val viewModel: ChatViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(conversationId) {
    viewModel.setConversationId(conversationId)
  }

  // Точка расширения для действий switch-карточек (реальные вкл/выкл-настройки приложения,
  // напр. toggle_daily_reminder). Запрос уведомлений вынесен в самодостаточную карточку
  // notification_permission, поэтому здесь действий пока нет.
  val onCardAction: (String, Boolean) -> Unit = { _, _ -> }

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
            interactiveCards = state.isTutorial,
            onChoiceSelected = { viewModel.sendUserMessage(it) },
            onFormSubmitted = { viewModel.sendUserMessage(it) },
            // Квиз JSON-урока пройден → показать карточку завершения.
            onLessonQuizComplete = { viewModel.handleIntent(ChatIntent.CompleteLessonQuiz) },
            onLessonComplete = {
              if (state.lessonMode) {
                // JSON-урок: отметить пройденным и вернуться к списку.
                viewModel.completeLesson(onNavigateBack)
              } else {
                // Старый roadmap-урок: финальная карточка ведёт на отдельный экран-квиз.
                val levelId = state.levelId
                if (levelId != null) onOpenQuiz(levelId) else onNavigateBack()
              }
            },
            onCardAction = onCardAction,
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
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = state.error!!,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
          )
          if (state.canRetry) {
            TextButton(onClick = { viewModel.handleIntent(ChatIntent.RetryRequest) }) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.size(4.dp))
              Text("Повторить")
            }
          }
        }
      }

      if (state.lessonMode) {
        // Урок драйвит контроллер: пользователь листает шаги кнопкой, текстовый инпут не нужен.
        val currentStep = state.lesson?.steps?.getOrNull(state.lessonStepIndex - 1)
        // Ручная кнопка нужна для текстового шага, а также для вырожденного quiz без вопросов —
        // у такого карточка не вызовет onAllAnswered, и без кнопки урок бы застрял.
        val needsManualAdvance = currentStep != null && !state.lessonFinished &&
          (currentStep.type == "text" || (currentStep.type == "quiz" && currentStep.questions.isEmpty()))
        if (needsManualAdvance) {
          val isLastStep = state.lessonStepIndex >= (state.lesson?.steps?.size ?: 0)
          LessonControlBar(
            label = if (isLastStep) "Завершить урок" else "Дальше →",
            onClick = { viewModel.handleIntent(ChatIntent.AdvanceLesson) },
          )
        }
      } else {
        // Режимы план/гоал — только в обычных чатах; в гайд-уроках (l2–l4) их не показываем.
        if (!state.isTutorial) {
          ModeSelector(
            selected = state.agentMode,
            enabled = !state.isLoading,
            onSelect = { viewModel.handleIntent(ChatIntent.SetMode(it)) },
          )
          if (state.planAwaitingApproval) {
            PlanApprovalBar(
              onApprove = { viewModel.handleIntent(ChatIntent.ApprovePlan) },
              onEdit = { viewModel.handleIntent(ChatIntent.EditPlan) },
            )
          }
        }
        ChatInput(
          value = state.inputText,
          onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
          onSend = { viewModel.handleIntent(ChatIntent.SendMessage) },
          onCancel = { viewModel.handleIntent(ChatIntent.CancelRequest) },
          isLoading = state.isLoading,
          placeholder = when (state.agentMode) {
            AgentMode.PLAN -> "Опиши задачу — агент составит план…"
            AgentMode.GOAL -> "Сформулируй цель…"
            AgentMode.CHAT -> "Сообщение..."
          },
        )
      }
    }
  }
}

@Composable
private fun ModeSelector(
  selected: AgentMode,
  enabled: Boolean,
  onSelect: (AgentMode) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ModeChip("Чат", selected == AgentMode.CHAT, enabled) { onSelect(AgentMode.CHAT) }
    ModeChip("План", selected == AgentMode.PLAN, enabled) { onSelect(AgentMode.PLAN) }
    ModeChip("Цель", selected == AgentMode.GOAL, enabled) { onSelect(AgentMode.GOAL) }
  }
}

@Composable
private fun ModeChip(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    enabled = enabled,
    label = { Text(label) },
  )
}

@Composable
private fun PlanApprovalBar(
  onApprove: () -> Unit,
  onEdit: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Text(
      text = "План готов. Выполнить?",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
        Text("Поправить")
      }
      Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
        Text("Выполнить")
      }
    }
  }
}

@Composable
private fun LessonControlBar(
  label: String,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Text(label)
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
  onLessonQuizComplete: () -> Unit = {},
  onCardAction: (String, Boolean) -> Unit = { _, _ -> },
) {
  val isUser = message.role == "user"
  val card = message.cardSpec?.takeIf { it.isNotBlank() }?.let { DrivenUiEngine.parseJsonString(it) }
  val hasCard = card != null
  // Уроки рисуют интерактивные виджеты; в обычном чате карточки показываются как markdown —
  // КРОМЕ switch: переключатель бессмыслен текстом, поэтому он интерактивен всегда.
  val interactive = hasCard && (interactiveCards ||
    card is DrivenUiCard.SwitchCard || card is DrivenUiCard.NotificationPermissionCard)

  // Markdown-режим: спецификация карточки рендерится как markdown, без интерактива.
  val cardMarkdown = card?.takeIf { !interactive }?.toMarkdown()
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
          onLessonQuizComplete = onLessonQuizComplete,
          onAction = onCardAction,
        )
      } else {
        MarkdownText(text = displayText)
      }
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
  placeholder: String = "Сообщение...",
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
      placeholder = { Text(placeholder) },
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
