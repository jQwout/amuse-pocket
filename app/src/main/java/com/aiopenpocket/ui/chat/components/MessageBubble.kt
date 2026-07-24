package com.aiopenpocket.ui.chat.components

// @trace streaming-llm-chat-6595a1
//
// Пузырь сообщения и полоска занятости контекста. Оба stateless: получают Message/ResponseStats
// и колбэки, ничего не знают о ViewModel — поэтому их же рендерят превью и скриншот-тесты.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.DrivenUiEngine
import com.aiopenpocket.ui.chat.ResponseStats
import com.aiopenpocket.ui.chat.toMarkdown
import com.aiopenpocket.ui.components.MarkdownText
import kotlinx.coroutines.flow.Flow

// internal, а не private: рендерится из превью-каркасов в ChatScreenPreviews.kt (тот же модуль).
@Composable
internal fun MessageBubble(
    message: Message,
    onDelete: () -> Unit = {},
    interactiveCards: Boolean = false,
    highlighted: Boolean = false,
    fontScale: Float = 1f,
    onChoiceSelected: (String) -> Unit = {},
    onFormSubmitted: (String) -> Unit = {},
    onLessonComplete: () -> Unit = {},
    onLessonQuizComplete: () -> Unit = {},
    onOpenAgentBuilder: () -> Unit = {},
    onCardAction: (String, Boolean) -> Unit = { _, _ -> },
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = { kotlinx.coroutines.flow.flowOf(null) },
    onCancelHeartbeat: (String) -> Unit = {},
) {
    // Панель действий (удалить) — по ТАПУ на пузырь, не по long-press: long-press занят выделением
    // текста (SelectionContainer внутри MarkdownText), с ним обработчик тапа не конфликтует.
    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isUser = message.role == "user"
    val card = remember(message.cardSpec) {
        message.cardSpec?.takeIf { it.isNotBlank() }?.let { DrivenUiEngine.parseJsonString(it) }
    }
    val hasCard = card != null
    // Карточки рисуем интерактивными виджетами только там, где они осмысленны:
    //  • уроки/туториал — карточка и есть содержимое шага;
    //  • свои сообщения — например вложенное фото (по просьбе оставляем карточкой);
    //  • функциональные контролы (переключатель/запрос уведомлений/reminder) — текстом бессмысленны:
    //    reminder вообще живая карточка, статичный markdown её не отразит.
    // Ответы агента в обычном чате показываем простым текстом (markdown) — легче и без подлагиваний.
    val interactive = hasCard && (interactiveCards || isUser ||
            card is DrivenUiCard.SwitchCard || card is DrivenUiCard.NotificationPermissionCard ||
            card is DrivenUiCard.ReminderCard)

    // Просто текст без карточки: если у ответа есть текст — показываем только его, не приклеивая
    // markdown-представление карточки. Карточку-«пустышку» без текста показываем как её markdown,
    // чтобы пузырь не оказался пустым.
    val displayText = remember(card, interactive, message.content) {
        val cardMarkdown = card?.takeIf { !interactive }?.toMarkdown()
        when {
            message.content.isNotBlank() -> message.content
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
    // Системное сообщение — не пузырь: рисуем без клипа и фоновой заливки.
    val isSystem = message.role == "system"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                // Системное сообщение — без пузыря: клип и фоновую заливку не применяем.
                .then(
                    if (isUser) {
                        Modifier .clip(RoundedCornerShape(16.dp))
                            .background(backgroundColor)
                    } else {
                        Modifier

                    },
                )
                // Подсветка текущего совпадения поиска — акцентная рамка вокруг пузыря.
                .then(
                    if (highlighted) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    } else {
                        Modifier
                    },
                )
                // Тап по пузырю — раскрыть/спрятать панель действий (удалить).
                .clickable(onClick = { showActions = !showActions })
                .padding(12.dp),
        ) {
            if (interactive) {
                if (message.content.isNotBlank()) {
                    MarkdownText(text = message.content, compact = true, selectable = true, fontScale = fontScale)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                RenderDrivenUiCard(
                    card = card,
                    onChoiceSelected = onChoiceSelected,
                    onFormSubmitted = onFormSubmitted,
                    onLessonComplete = onLessonComplete,
                    onLessonQuizComplete = onLessonQuizComplete,
                    onOpenAgentBuilder = onOpenAgentBuilder,
                    onAction = onCardAction,
                    observeHeartbeat = observeHeartbeat,
                    onCancelHeartbeat = onCancelHeartbeat,
                    lessonCard = interactiveCards,
                )
            } else {
                MarkdownText(text = displayText, compact = true, selectable = true, fontScale = fontScale)
            }
            AnimatedVisibility(visible = showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // В уроке шаг чата ведёт себя по счётчику ассистентских сообщений
                    // (ChatViewModel.startOrResumeLesson) — удаление любого из них десинхронизирует
                    // курсор урока (повтор или пропуск шага). Функциональные карточки (switch/разрешение/reminder)
                    // не часть шагового счёта, поэтому их можно удалять и внутри урока — как и свои сообщения.
                    val isLessonStepContent = interactiveCards &&
                            card !is DrivenUiCard.SwitchCard &&
                            card !is DrivenUiCard.NotificationPermissionCard &&
                            card !is DrivenUiCard.ReminderCard
                    if (!isLessonStepContent || isUser) {
                        IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить сообщение?") },
            text = { Text("Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; showActions = false; onDelete() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

// internal: см. MessageBubble — нужен превью-каркасу.
@Composable
internal fun ResponseStatsBar(
    stats: ResponseStats,
    modifier: Modifier = Modifier,
) {
    // Вся статистика хода сведена к одной полоске занятости контекста: цифры времени/токенов
    // рядом с ней жили в закомментированном Row и только шумели — история осталась в git.
    val contextUsedFraction = (stats.contextTokensUsed.toFloat() / stats.contextBudget).coerceIn(0f, 1f)

    LinearProgressIndicator(
        progress = { contextUsedFraction },
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = when {
            contextUsedFraction > 0.85f -> MaterialTheme.colorScheme.error
            contextUsedFraction > 0.65f -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

