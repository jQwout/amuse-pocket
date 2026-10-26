package com.aiopenpocket.ui.chat.components

// @trace streaming-llm-chat-6595a1
//
// The message bubble and the context usage strip. Both are stateless: they take Message/ResponseStats and
// callbacks and know nothing about the ViewModel — so previews and screenshot tests render them too.

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
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.DrivenUiEngine
import com.aiopenpocket.ui.chat.ResponseStats
import com.aiopenpocket.ui.chat.toMarkdown
import com.aiopenpocket.ui.components.MarkdownText
import kotlinx.coroutines.flow.Flow

// internal, not private: rendered from the preview frames in ChatScreenPreviews.kt (the same module).
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
    personalizingImages: Set<String> = emptySet(),
    onPersonalizeImage: (DrivenUiCard.ImageCard) -> Unit = {},
    analyzingVideos: Set<String> = emptySet(),
    onLessonVideo: (String, android.net.Uri) -> Unit = { _, _ -> },
) {
    // The action panel (delete) opens on a TAP on the bubble, not a long press: the long press is taken by
    // text selection (the SelectionContainer inside MarkdownText), and the tap handler does not conflict with it.
    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isUser = message.role == "user"
    val card = remember(message.cardSpec) {
        message.cardSpec?.takeIf { it.isNotBlank() }?.let { DrivenUiEngine.parseJsonString(it) }
    }
    val hasCard = card != null
    // Cards are drawn as interactive widgets:
    //  • lessons/tutorial — any card (there it is the step's content);
    //  • the user's own messages — e.g. an attached photo;
    //  • the shared render_card templates (table, chart, to-do list, form, image, reminder…) — IN ANY chat:
    //    the system prompt and the budget/fitness/languages skills ask the agent to draw charts and tables,
    //    and outside a lesson such an answer used to collapse into text, with the card gone entirely.
    // Lesson cards (quiz/reveal/fill…) outside a lesson stay markdown: without a lesson there is nowhere to
    // count the answer.
    val interactive = hasCard && (interactiveCards || isUser || card.isGeneralWidget())

    // A non-interactive card: if the answer has text, only the text is shown, without gluing on the card's
    // markdown representation. A placeholder card without text is shown as its markdown so the bubble is
    // not empty.
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
    // A system message is not a bubble: drawn without the clip and the background fill.
    val isSystem = message.role == "system"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                // A system message has no bubble: the clip and the background fill are not applied.
                .then(
                    if (isUser) {
                        Modifier .clip(RoundedCornerShape(16.dp))
                            .background(backgroundColor)
                    } else {
                        Modifier

                    },
                )
                // Highlighting the current search match — an accent frame around the bubble.
                .then(
                    if (highlighted) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    } else {
                        Modifier
                    },
                )
                // A tap on the bubble expands/hides the action panel (delete).
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
                    personalizingImages = personalizingImages,
                    onPersonalizeImage = onPersonalizeImage,
                    analyzingVideos = analyzingVideos,
                    onLessonVideo = onLessonVideo,
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
                    // Inside a lesson, step messages cannot be deleted. The guard was added when the lesson cursor was
                    // derived from the count of assistant messages (ChatViewModel.startOrResumeLesson), so
                    // deleting one desynced it (a step repeated or skipped); the cursor is explicit state now
                    // (lessonStepIndex), so that reason no longer holds. Functional cards (switch/permission/
                    // reminder) were never part of the step count, so they can be deleted inside a lesson too
                    // — as can the user's own messages.
                    val isLessonStepContent = interactiveCards &&
                            card !is DrivenUiCard.SwitchCard &&
                            card !is DrivenUiCard.NotificationPermissionCard &&
                            card !is DrivenUiCard.ReminderCard
                    if (!isLessonStepContent || isUser) {
                        IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = tr("Удалить", "Delete"),
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
            title = { Text(tr("Удалить сообщение?", "Delete this message?")) },
            text = { Text(tr("Это действие нельзя отменить.", "This can't be undone.")) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; showActions = false; onDelete() }) {
                    Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }
}

/**
 * The shared render_card templates (not lessons): each has its own widget, and the meaning of the card is
 * in it, not in the text. [DrivenUiCard.ErrorCard] is not included: a broken spec next to a normal answer
 * is only noise. The weather and the switch are no longer offered, but old messages with them render as
 * before.
 */
private fun DrivenUiCard?.isGeneralWidget(): Boolean = when (this) {
    is DrivenUiCard.TodoList,
    is DrivenUiCard.WeatherCard,
    is DrivenUiCard.TimerCard,
    is DrivenUiCard.TableCard,
    is DrivenUiCard.ChartBarCard,
    is DrivenUiCard.FormCard,
    is DrivenUiCard.ChoiceCard,
    is DrivenUiCard.SwitchCard,
    is DrivenUiCard.NotificationPermissionCard,
    is DrivenUiCard.ReminderCard,
    is DrivenUiCard.ImageCard,
    -> true
    else -> false
}

// internal: see MessageBubble — the preview frame needs it.
@Composable
internal fun ResponseStatsBar(
    stats: ResponseStats,
    modifier: Modifier = Modifier,
) {
    // All the turn statistics are reduced to one context usage strip: the time/token figures next to it lived
    // in a commented-out Row and were only noise — the history is in git.
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

