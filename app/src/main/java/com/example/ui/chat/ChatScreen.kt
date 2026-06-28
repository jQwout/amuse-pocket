package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
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
          // Переключатель трассы инструментов показываем ТОЛЬКО когда трасса есть — иначе кнопка была бы
          // «мёртвой» (раньше это была шестерёнка с пустым меню). Режим агента выбирается селектором
          // над полем ввода.
          if (state.toolTrace.isNotEmpty()) {
            IconButton(onClick = { viewModel.handleIntent(ChatIntent.ToggleToolTrace) }) {
              Icon(
                Icons.Default.Build,
                contentDescription = if (state.showToolTrace) "Скрыть инструменты" else "Показать инструменты",
                tint = if (state.showToolTrace) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
        .padding(padding)
        // Scaffold уже потребил системные бары в padding; сообщаем об этом, чтобы imePadding
        // не двойного счёл нижний инсет и поднимал ввод ровно на высоту клавиатуры.
        .consumeWindowInsets(padding)
        .imePadding(),
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

      // Ошибка распознавания — мягкая самоисчезающая подсказка, а не висящий баннер ошибки.
      val voiceError = state.voiceError
      if (voiceError != null) {
        LaunchedEffect(voiceError) {
          kotlinx.coroutines.delay(2500)
          viewModel.handleIntent(ChatIntent.ClearVoiceError)
        }
        Row(
          modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.MicOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = voiceError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
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
        // Ручная кнопка — для шагов без собственного «завершения»: текст, картинка и карточки-
        // отображения (reveal/concept/example). Интерактивные (quiz/fill/sort/match/scenario) двигают
        // урок сами по факту прохождения; исключение — вырожденный quiz без вопросов, ему нужна кнопка.
        val displayStep = currentStep?.type in
          setOf("text", "image", "reveal", "concept", "example", "input", "challenge")
        val needsManualAdvance = currentStep != null && !state.lessonFinished &&
          (displayStep || (currentStep.type == "quiz" && currentStep.questions.isEmpty()))
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
        // Во время записи поле ввода подменяется панелью записи (как в Telegram/Claude): слева —
        // отмена, по центру статус/таймер/волна/живой текст, справа — завершение. Итог падает в поле.
        // Переключение голос↔текст анимируется: fade + лёгкий сдвиг + плавная смена высоты бара.
        AnimatedContent(
          targetState = state.voiceState is VoiceInputState.Listening,
          transitionSpec = {
            (fadeIn(animationSpec = tween(220)) +
              slideInVertically(animationSpec = tween(220)) { it / 3 }) togetherWith
              (fadeOut(animationSpec = tween(160)) +
                slideOutVertically(animationSpec = tween(160)) { it / 3 })
          },
          label = "input-mode",
        ) { isListening ->
          if (isListening) {
            VoiceRecordingBar(
              partial = (state.voiceState as? VoiceInputState.Listening)?.partial,
              onCancel = { viewModel.handleIntent(ChatIntent.CancelVoiceInput) },
              onDone = { viewModel.handleIntent(ChatIntent.ToggleVoiceInput) },
            )
          } else {
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
              onExpand = { composerExpanded = true },
              voiceState = state.voiceState,
              onVoiceToggle = { viewModel.handleIntent(ChatIntent.ToggleVoiceInput) },
              onPermissionDenied = { viewModel.handleIntent(ChatIntent.ClearVoiceError) },
            )
          }
        }
      }
    }
  }

    // Полноэкранный композер: тот же state.inputText, поверх чата. Отправка/Очистка идут теми же
    // интентами, что и инлайновый ввод, поэтому два режима всегда показывают один и тот же текст.
    // Появление/скрытие анимируем: fade + лёгкий зум, чтобы «разворот» ощущался плавным.
    AnimatedVisibility(
      visible = composerExpanded,
      enter = fadeIn(animationSpec = tween(200)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
      exit = fadeOut(animationSpec = tween(160)) +
        scaleOut(targetScale = 0.92f, animationSpec = tween(180)),
    ) {
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

/**
 * Панель записи голоса в стиле Telegram/Claude — подменяет поле ввода, пока идёт распознавание.
 * Слева кнопка-корзина (отмена без вставки текста), по центру пилюля со статусом «Слушаю»,
 * таймером, анимированной «волной» и живым (partial) текстом, справа — акцентная кнопка завершения.
 * По завершении распознанный текст контроллер кладёт в поле ввода; отправляет пользователь сам.
 */
@Composable
private fun VoiceRecordingBar(
  partial: String?,
  onCancel: () -> Unit,
  onDone: () -> Unit,
) {
  var seconds by remember { mutableStateOf(0) }
  LaunchedEffect(Unit) {
    while (true) {
      kotlinx.coroutines.delay(1000)
      seconds++
    }
  }
  val transition = rememberInfiniteTransition(label = "rec-pulse")
  val dotAlpha by transition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 650),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "rec-dot",
  )

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Отмена записи — сбрасывает распознавание без вставки текста.
    IconButton(
      onClick = onCancel,
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Icon(
        Icons.Default.Delete,
        contentDescription = "Отменить запись",
        tint = MaterialTheme.colorScheme.error,
      )
    }
    Spacer(Modifier.width(8.dp))
    // Центральная пилюля: статус + таймер + волна + живой текст.
    Column(
      modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = "Слушаю",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(1f))
        Text(
          text = formatRecordTime(seconds),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        VoiceWave(color = MaterialTheme.colorScheme.error)
      }
      Spacer(Modifier.height(4.dp))
      Text(
        text = partial?.takeIf { it.isNotBlank() } ?: "Говорите — текст появится здесь",
        style = MaterialTheme.typography.bodyMedium,
        color = if (partial.isNullOrBlank()) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSurface
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(8.dp))
    // Завершить запись — та же акцентная круглая кнопка «Отправить», что и в обычном поле ввода.
    IconButton(
      onClick = onDone,
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary),
    ) {
      Icon(
        Icons.AutoMirrored.Default.Send,
        contentDescription = "Готово",
        tint = MaterialTheme.colorScheme.onPrimary,
      )
    }
  }
}

/** Анимированная «звуковая волна» из нескольких полосок со сдвигом фазы — индикатор записи. */
@Composable
private fun VoiceWave(color: Color) {
  val transition = rememberInfiniteTransition(label = "wave")
  val delays = listOf(0, 180, 90, 270, 140)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    delays.forEachIndexed { i, delayMs ->
      val h by transition.animateFloat(
        initialValue = 5f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
          animation = tween(durationMillis = 480, delayMillis = delayMs),
          repeatMode = RepeatMode.Reverse,
        ),
        label = "wave-bar-$i",
      )
      Box(
        modifier = Modifier
          .width(3.dp)
          .height(h.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(color),
      )
    }
  }
}

private fun formatRecordTime(totalSeconds: Int): String {
  val m = totalSeconds / 60
  val s = totalSeconds % 60
  return "%d:%02d".format(m, s)
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

  // Единая скруглённая рамка: сверху — текст, снизу — панель управления (как в макете).
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 8.dp)
      .clip(RoundedCornerShape(24.dp))
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        shape = RoundedCornerShape(24.dp),
      )
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 24.dp, max = 160.dp)
        .padding(horizontal = 4.dp, vertical = 4.dp),
      textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      maxLines = 6,
      decorationBox = { inner ->
        if (value.isEmpty()) {
          // Плейсхолдер тонирован под акцент (как фиолетовая рамка), чтобы отличаться от текста.
          Text(placeholder, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        }
        inner()
      },
    )
    Spacer(Modifier.height(6.dp))
    // Нижняя панель внутри рамки: слева — развернуть и микрофон, справа — очистить и отправить.
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.Default.OpenInFull,
          contentDescription = "Развернуть",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
      }
      Spacer(Modifier.width(4.dp))
      MicButton(state = voiceState, onClick = ::handleMicTap, modifier = Modifier.size(36.dp))
      Spacer(Modifier.weight(1f))
      // Очистить поле — показываем только когда есть текст.
      if (value.isNotBlank()) {
        IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
          Icon(
            Icons.Default.Close,
            contentDescription = "Очистить",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
        Spacer(Modifier.width(4.dp))
      }
      if (isLoading) {
        // Прогресс вокруг кнопки «стоп» — даёт пользователю возможность отменить ход.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 2.dp)
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
            .size(44.dp)
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
        // Композер — полноэкранный поверх Scaffold, поэтому сам учитывает статус-бар, навбар и
        // клавиатуру. safeDrawingPadding = max(системные бары, IME), без двойного счёта навбара.
        .safeDrawingPadding(),
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
