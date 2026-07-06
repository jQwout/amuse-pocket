package com.aiopenpocket.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.aiopenpocket.agent.loop.AgentMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.memory.model.Message
import dev.zacsweers.metrox.viewmodel.metroViewModel
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.chat.components.MicButton
import com.aiopenpocket.ui.chat.components.ToolTracePanel
import com.aiopenpocket.voice.input.VoiceInputState

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
  var menuOpen by remember { mutableStateOf(false) }
  var composerExpanded by remember { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize()) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
            }
            Spacer(Modifier.width(10.dp))
            Column {
              Text(
                text = state.title ?: "Чат",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ONLINE_GREEN),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                  text = "онлайн",
                  style = MaterialTheme.typography.labelSmall,
                  color = ONLINE_GREEN,
                )
              }
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Назад")
          }
        },
        actions = {
          // Шестерёнка открывает меню чата (трасса инструментов). Режим агента (Чат/План/Цель) теперь
          // выбирается селектором над полем ввода, а не из этого меню.
          IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.Settings, contentDescription = "Настройки чата")
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state.toolTrace.isNotEmpty()) {
              DropdownMenuItem(
                text = { Text(if (state.showToolTrace) "Скрыть инструменты" else "Показать инструменты") },
                onClick = {
                  viewModel.handleIntent(ChatIntent.ToggleToolTrace)
                  menuOpen = false
                },
                leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
              )
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

      if (state.voiceError != null) {
        Text(
          text = state.voiceError!!,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
      }

      if (!state.isLoading && state.lastResponseStats != null) {
        ResponseStatsBar(
          stats = state.lastResponseStats!!,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
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
            AgentMode.REFLECT -> "Спроси — агент даст черновик, критику и итог…"
            AgentMode.CHAT -> "Сообщение..."
          },
          onExpand = { composerExpanded = true },
          voiceState = state.voiceState,
          onVoiceToggle = { viewModel.handleIntent(ChatIntent.ToggleVoiceInput) },
          onPermissionDenied = { viewModel.handleIntent(ChatIntent.ClearVoiceError) },
        )
      }
    }
  }

    // Полноэкранный композер: тот же state.inputText, поверх чата. Отправка/Очистка идут теми же
    // интентами, что и инлайновый ввод, поэтому два режима всегда показывают один и тот же текст.
    if (composerExpanded) {
      FullScreenComposer(
        value = state.inputText,
        onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
        onClear = { viewModel.handleIntent(ChatIntent.UpdateInput("")) },
        onCollapse = { composerExpanded = false },
        onSend = {
          viewModel.handleIntent(ChatIntent.SendMessage)
          composerExpanded = false
        },
      )
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
    ModeChip("Рефлексия", selected == AgentMode.REFLECT, enabled) { onSelect(AgentMode.REFLECT) }
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
  val card = remember(message.cardSpec) {
    message.cardSpec?.takeIf { it.isNotBlank() }?.let { DrivenUiEngine.parseJsonString(it) }
  }
  val hasCard = card != null
  // Уроки рисуют интерактивные виджеты; в обычном чате карточки показываются как markdown —
  // КРОМЕ switch: переключатель бессмыслен текстом, поэтому он интерактивен всегда.
  val interactive = hasCard && (interactiveCards ||
    card is DrivenUiCard.SwitchCard || card is DrivenUiCard.NotificationPermissionCard)

  // Markdown-режим: спецификация карточки рендерится как markdown, без интерактива.
  val displayText = remember(card, interactive, message.content) {
    val cardMarkdown = card?.takeIf { !interactive }?.toMarkdown()
    when {
      cardMarkdown != null && message.content.isNotBlank() -> "${message.content}\n\n$cardMarkdown"
      cardMarkdown != null -> cardMarkdown
      else -> message.content
    }
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

@Composable
private fun ResponseStatsBar(
  stats: ResponseStats,
  modifier: Modifier = Modifier,
) {
  val timeLabel = if (stats.responseTimeMs >= 1000) {
    "%.1fс".format(stats.responseTimeMs / 1000f)
  } else {
    "${stats.responseTimeMs}мс"
  }
  val contextUsedFraction = (stats.contextTokensUsed.toFloat() / stats.contextBudget).coerceIn(0f, 1f)
  val contextRemainingK = ((stats.contextBudget - stats.contextTokensUsed) / 1000)
    .coerceAtLeast(0)

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "⏱ $timeLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "↩ ~${stats.completionTokens} токенов",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "контекст: ~${contextRemainingK}k осталось",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.height(2.dp))
    LinearProgressIndicator(
      progress = { contextUsedFraction },
      modifier = Modifier.fillMaxWidth().height(2.dp),
      color = when {
        contextUsedFraction > 0.85f -> MaterialTheme.colorScheme.error
        contextUsedFraction > 0.65f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
      },
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
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
  onExpand: () -> Unit,
  voiceState: VoiceInputState,
  onVoiceToggle: () -> Unit,
  onPermissionDenied: () -> Unit,
) {
  val context = LocalContext.current
  var pendingStart by remember { mutableStateOf(false) }
  val micPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted && pendingStart) onVoiceToggle()
    if (!granted) onPermissionDenied()
    pendingStart = false
  }

  fun handleMicTap() {
    val granted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) {
      onVoiceToggle()
    } else {
      pendingStart = true
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      placeholder = { Text(placeholder) },
      shape = RoundedCornerShape(24.dp),
      maxLines = 6,
      trailingIcon = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
              Icon(Icons.Default.Close, contentDescription = "Очистить", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(2.dp))
          }
          IconButton(onClick = onExpand, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.OpenInFull, contentDescription = "Развернуть", modifier = Modifier.size(18.dp))
          }
          Spacer(Modifier.width(4.dp))
        }
      },
    )
    Spacer(Modifier.width(6.dp))
    MicButton(state = voiceState, onClick = ::handleMicTap)
    if (isLoading) {
      // Прогресс вокруг кнопки «стоп» — даёт пользователю возможность отменить ход.
      Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 2.dp)
        IconButton(onClick = onCancel) {
          Icon(Icons.Default.Stop, contentDescription = "Остановить")
        }
      }
    } else {
      // Кнопка-кружок: залита акцентом, когда есть что отправить; иначе приглушена (как в макете).
      val canSend = value.isNotBlank()
      IconButton(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Icon(
          Icons.AutoMirrored.Default.Send,
          contentDescription = "Отправить",
          tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Полноэкранный режим набора (иконка «развернуть» в инлайновом вводе). Редактирует тот же текст,
 * что и [ChatInput]; «Свернуть» прячет режим без потери набранного, «Очистить» обнуляет, «Отправить»
 * шлёт и сворачивает.
 */
@Composable
private fun FullScreenComposer(
  value: String,
  onValueChange: (String) -> Unit,
  onClear: () -> Unit,
  onCollapse: () -> Unit,
  onSend: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .imePadding(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onCollapse) {
          Icon(Icons.Default.CloseFullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Свернуть")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClear, enabled = value.isNotEmpty()) {
          Text("Очистить")
        }
      }
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Напишите сообщение...") },
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = Color.Transparent,
          unfocusedBorderColor = Color.Transparent,
        ),
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        Button(onClick = onSend, enabled = value.isNotBlank()) {
          Text("Отправить")
          Spacer(Modifier.width(8.dp))
          Icon(Icons.AutoMirrored.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

/** Цвет индикатора «онлайн» в шапке чата. */
private val ONLINE_GREEN = Color(0xFF4CAF50)
