package com.aiopenpocket.ui.chat.components

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
//
// The chat screen's bars: search, mode selection, tool confirmation, voice recording and lesson control.
// Moved out of ChatScreen.kt — each is self-contained and talks to the screen only through parameters, so
// there was no reason to keep them in the screen's body.

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.riskClassLabel
import com.aiopenpocket.ui.agents.builder.components.riskColor
import com.aiopenpocket.ui.components.glass.GlassCard
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The search row in the header: the input field + the match counter and navigation arrows (prev/next),
 * the cross closes the search. Scrolling to matches and highlighting the bubble happen in ChatScreen.
 */
@Composable
internal fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    containerColor: Color,
) {
    Surface(color = containerColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The status bar inset comes from the parent glass "pill" (topBar); not duplicated here.
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = tr("Закрыть поиск", "Close search"))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            tr("Поиск по сообщениям…", "Search messages…"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotBlank()) {
                Text(
                    text = "$currentMatch/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = tr("Предыдущее", "Previous"))
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = tr("Следующее", "Next"))
                }
            }
        }
    }
}

/**
 * Which agent modes a chat may offer. PLAN/GOAL require a resolvable agent (the planner refuses
 * without one), so a plain chat (no bound agent) gets only Chat + Reflect — showing Plan/Goal there
 * would be a dead-end. An agent-bound chat gets exactly the agent's own [AgentDefinition.allowedModes]
 * (what the builder let the user restrict), always including Chat as the base mode, rendered in a
 * stable display order.
 */
internal fun availableModesFor(agent: AgentDefinition?): List<AgentMode> {
  if (agent == null) return listOf(AgentMode.CHAT, AgentMode.REFLECT)
  // Exactly the agent's own allowedModes, in a stable display order. Chat used to be force-added even
  // when the builder had explicitly excluded it, so an agent authored as "GOAL only" still offered —
  // and opened in — plain chat, quietly undoing that setting. TUTOR is lesson-internal and never
  // appears here (it is not in the display order). Empty is impossible (AgentValidator requires
  // defaultMode ∈ allowedModes, and the wizard refuses to empty the set), but fall back to Chat
  // rather than render a selector with no options at all if a hand-edited row ever gets there.
  val displayOrder = listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT)
  return displayOrder.filter { it in agent.allowedModes }.ifEmpty { listOf(AgentMode.CHAT) }
}

// internal: called both from the chat screen and from the preview frame.
@Composable
internal fun ModeSelector(
    selected: AgentMode,
    enabled: Boolean,
    available: List<AgentMode>,
    onSelect: (AgentMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        available.forEach { mode ->
            ModeChip(modeLabel(mode), selected == mode, enabled) { onSelect(mode) }
        }
    }
}

private fun modeLabel(mode: AgentMode): String = when (mode) {
    AgentMode.CHAT -> tr("Чат", "Chat")
    AgentMode.PLAN -> tr("План", "Plan")
    AgentMode.GOAL -> tr("Цель", "Goal")
    AgentMode.REFLECT -> tr("Рефлексия", "Reflect")
    else -> mode.name // TUTOR is lesson-internal and never offered in this selector.
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

/**
 * The tool confirmation modal/bar (Task 9 backend, Task 21 UI): shown while
 * `state.pendingToolApproval` is not null — the agent's BEFORE_TOOL hook required permission before
 * calling a tool. Shows the call's human name ([CapabilityNames.call]), the risk class (the same badge
 * style as on the agent screen — [riskClassLabel]/[riskColor]) and the call's arguments; "Allow"/"Decline"
 * send [ChatIntent.ApproveToolCall]/[ChatIntent.DenyToolCall] — ChatViewModel resolves them itself against
 * the CURRENT `state.pendingToolApproval`, here sending the intent is enough.
 */
@Composable
internal fun ToolApprovalBar(
    request: ToolApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat_tool_approval"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("Агенту нужно разрешение", "The agent needs your permission"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                val badgeColor = riskColor(request.riskClass)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = riskClassLabel(request.riskClass),
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // What exactly the agent wants to do, in human terms ("Calendar: delete event"), not a raw `calendar`
            // in a monospace font: the user is the one deciding to "allow".
            Text(
                text = CapabilityNames.call(request.toolName, request.arguments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val argumentsText = formatToolArguments(request.toolName, request.arguments)
            if (argumentsText.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = argumentsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) {
                    Text(tr("Отклонить", "Decline"))
                }
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Text(tr("Разрешить", "Allow"))
                }
            }
        }
    }
}

/**
 * The call's arguments under the bar's title. `action` (and a skill's `id` for load_skill) are already
 * said in the title through [CapabilityNames.call] — no need to repeat them below as a raw id. Strings are
 * shown without JSON quotes.
 */
private fun formatToolArguments(toolName: String, arguments: Map<String, JsonElement>): String =
    arguments.entries
        .filterNot { (key, _) -> key == "action" || (toolName == "load_skill" && key == "id") }
        .joinToString(", ") { (key, value) -> "$key: ${(value as? JsonPrimitive)?.content ?: value}" }

/**
 * A Telegram/Claude-style voice recording panel — it replaces the input field while recognition runs. On
 * the left a trash button (cancel without inserting text), in the middle a pill with the "Listening"
 * status, a timer, an animated "wave" and live (partial) text, on the right an accent finish button. When
 * finished, the controller puts the recognized text into the input field; the user sends it themselves.
 */
@Composable
internal fun VoiceRecordingBar(
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
        // Cancel recording — drops the recognition without inserting text.
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = tr("Отменить запись", "Cancel recording"),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.width(8.dp))
        // The central pill: status + timer + wave + live text.
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
                    text = tr("Слушаю", "Listening"),
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
                text = partial?.takeIf { it.isNotBlank() } ?: tr("Говорите — текст появится здесь", "Speak — the text will appear here"),
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
        // Finish recording — the same round accent "Send" button as in the normal input field.
        IconButton(
            onClick = onDone,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                Icons.AutoMirrored.Default.Send,
                contentDescription = tr("Готово", "Done"),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** An animated "sound wave" of several bars with a phase shift — the recording indicator. */
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
internal fun LessonControlBar(
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

/**
 * "Ask the AI" during a lesson: a collapsed pill that expands into an input field + send on tap. The
 * question goes as a normal turn ([ChatIntent.AskDuringLesson]); the lesson cursor is persisted separately
 * and does not move. While answering (enabled=false) input is blocked, so as not to spawn parallel turns.
 */
@Composable
internal fun LessonAskBar(
    enabled: Boolean,
    onAsk: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        val q = text.trim()
        if (q.isEmpty()) return
        onAsk(q)
        text = ""
        expanded = false
    }

    if (!expanded) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Спросить у ИИ", "Ask the AI"))
        }
    } else {
        // The field was expanded — raise the keyboard and place the cursor right away (otherwise a second tap is needed).
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { expanded = false; text = "" }) {
                Icon(Icons.Default.Close, contentDescription = tr("Закрыть", "Close"))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(tr("Спроси про урок…", "Ask about the lesson…")) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = { submit() }, enabled = enabled && text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Отправить вопрос", "Send question"))
            }
        }
    }
}
