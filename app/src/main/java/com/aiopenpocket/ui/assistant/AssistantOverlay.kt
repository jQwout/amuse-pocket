package com.aiopenpocket.ui.assistant

// @trace voice-assistant-mode-a81a99

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.agent.RouteChoice
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.ChatIntent
import com.aiopenpocket.ui.chat.ChatViewModel
import com.aiopenpocket.ui.chat.components.MessageBubble
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.theme.LimeOnDark
import com.aiopenpocket.ui.theme.LimeSignal
import com.aiopenpocket.voice.input.VoiceInputState
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val ScrimInk = Color(0xFF0B100C)

/**
 * The phone's assistant, drawn over whatever app was on screen (the owner's earlier assistant was the
 * model for it): a dimmed backdrop, the agent chips, the orb, what was heard, and the answer in place.
 *
 * It is a front end for [ChatViewModel], not a second chat: the turn runs through the same router,
 * approvals, reply-aloud and history as a chat opened by hand, so what was asked here is also in the
 * conversation list, and "Open in chat" simply continues it in the app.
 */
@Composable
internal fun AssistantOverlay(
    onDismiss: () -> Unit,
    onOpenChat: (conversationId: String?) -> Unit,
) {
    val viewModel: ChatViewModel = metroViewModel()
    val state by viewModel.state.collectAsState()
    val streaming by viewModel.streamingText.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.setConversationId("")
        viewModel.handleIntent(ChatIntent.EnterVoiceAssistant)
    }

    var pendingStart by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingStart) viewModel.handleIntent(ChatIntent.ToggleVoiceInput)
        pendingStart = false
    }
    fun toggleListening() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.handleIntent(ChatIntent.ToggleVoiceInput)
        } else {
            pendingStart = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(state.autoListen) {
        if (state.autoListen) {
            viewModel.handleIntent(ChatIntent.AutoListenStarted)
            toggleListening()
        }
    }

    val messages = state.messages
    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    val question = messages.getOrNull(lastUserIndex)
    val reply = if (lastUserIndex < 0) null else messages.drop(lastUserIndex + 1).lastOrNull { it.role == "assistant" }
    val listening = state.voiceState as? VoiceInputState.Listening
    val thinking = state.isLoading
    val answered = question != null

    val orbBias by animateFloatAsState(
        targetValue = if (answered) -0.55f else 0.55f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "orbBias",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to ScrimInk.copy(alpha = 0.15f),
                    0.55f to ScrimInk.copy(alpha = 0.7f),
                    0.9f to ScrimInk.copy(alpha = 0.97f),
                    1f to ScrimInk,
                ),
            )
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) {
            Icon(Icons.Default.Close, tr("Закрыть", "Close"), tint = Color.White)
        }

        Box(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            contentAlignment = BiasAlignment(0f, orbBias),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // What was heard: live while listening, then the question itself, in italics, as in the sample.
                Text(
                    text = when {
                        question != null -> question.content
                        listening != null -> listening.partial?.takeIf { it.isNotBlank() } ?: tr("Слушаю…", "Listening…")
                        else -> tr("Что сделать?", "What can I do?")
                    },
                    color = if (question != null) Color.White.copy(alpha = 0.85f) else Color.White,
                    fontSize = if (question != null) 24.sp else 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (question != null) FontStyle.Italic else FontStyle.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )

                // Who answers: the router (Auto) or one agent, the same choice as the chip in a chat.
                AnimatedVisibility(visible = !answered && state.canRoute && state.routeAgents.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        RouteChip(tr("Авто", "Auto"), state.routeChoice == RouteChoice.Auto) {
                            viewModel.handleIntent(ChatIntent.SetRouteChoice(RouteChoice.Auto))
                        }
                        state.routeAgents.forEach { agent ->
                            val picked = state.routeChoice == RouteChoice.Fixed(agent.id)
                            RouteChip(agent.name, picked) {
                                viewModel.handleIntent(
                                    ChatIntent.SetRouteChoice(if (picked) RouteChoice.Auto else RouteChoice.Fixed(agent.id)),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                AssistantOrb(
                    state = when {
                        thinking -> OrbState.THINKING
                        listening != null -> OrbState.LISTENING
                        else -> OrbState.IDLE
                    },
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { if (!thinking) toggleListening() },
                )

                val agentName = state.routedAgentName ?: reply?.let { state.routedNameOf(it) }
                Text(
                    text = when {
                        thinking && agentName != null -> tr("→ $agentName думает…", "→ $agentName is thinking…")
                        thinking -> tr("Думаю…", "Thinking…")
                        listening != null -> ""
                        reply != null && agentName != null -> tr("Готово · → $agentName", "Done · → $agentName")
                        reply != null -> tr("Готово", "Done")
                        else -> tr("Нажми на шар и говори", "Tap the orb and speak")
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )

                AnimatedVisibility(
                    visible = answered,
                    enter = fadeIn(tween(400, 200)) + expandVertically(),
                    exit = fadeOut(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState()),
                    ) {
                        val approval = state.pendingToolApproval
                        val error = state.error
                        when {
                            approval != null -> ToolApprovalBar(
                                request = approval,
                                onApprove = { viewModel.handleIntent(ChatIntent.ApproveToolCall(approval.requestId)) },
                                onDeny = { viewModel.handleIntent(ChatIntent.DenyToolCall(approval.requestId)) },
                            )
                            reply != null && !thinking -> MessageBubble(message = reply)
                            thinking && streaming.isNotBlank() -> AnswerPlate { MarkdownText(streaming, compact = true) }
                            error != null && !thinking -> AnswerPlate {
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (!thinking) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 18.dp),
                            ) {
                                FilledTonalButton(onClick = { toggleListening() }) {
                                    Text(tr("Спросить ещё", "Ask more"))
                                }
                                OutlinedButton(onClick = { onOpenChat(state.conversationId) }) {
                                    Text(tr("Открыть в чате", "Open in chat"), color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        color = if (selected) LimeOnDark else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) LimeSignal else Color.White.copy(alpha = 0.1f))
            .border(1.dp, if (selected) LimeSignal else Color.White.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AnswerPlate(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(16.dp),
    ) { content() }
}
