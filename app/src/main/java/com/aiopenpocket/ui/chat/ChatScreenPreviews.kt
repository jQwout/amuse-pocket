package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
//
// The chat screen previews were moved here from ChatScreen.kt: it had grown past two thousand lines,
// and the frame with stubs got in the way of reading the production code. Only visual frames on fake data
// live here — no ViewModel, no DI, no network. Screenshot tests render the same ones.

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.ui.chat.components.ChatInput
import com.aiopenpocket.ui.chat.components.MessageBubble
import com.aiopenpocket.ui.chat.components.ModeSelector
import com.aiopenpocket.ui.chat.components.ResponseStatsBar
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.aiopenpocket.voice.input.VoiceInputState

// ==================================================================================================
// Chat screen previews (the Android Studio panel / screenshot tests). They render the VISUAL frame on fake
// data without ViewModel/DI/network — the same MyApplicationTheme and the same stateless bubbles
// (MessageBubble/StreamingBubble) as in production. The real ChatScreen's scroll effects need a runtime
// and do not take part in previews — here we look at HOW the chat LOOKS in different states.
// ==================================================================================================

/** A fake message for previews. The id includes the cardSpec so placeholder cards do not share keys. */
internal fun previewMessage(
    role: String,
    content: String,
    cardSpec: String? = null,
) = Message(
    id = "$role-${content.hashCode()}-${cardSpec?.hashCode() ?: 0}",
    conversationId = "preview",
    role = role,
    content = content,
    toolCallsJson = null,
    cardSpec = cardSpec,
    createdAt = 0L,
)

/**
 * The chat frame for previews — the same layout as the production [ChatScreen]: the aurora source layer,
 * the feed across the whole screen under FLOATING glass bars (the header pill on top, the composer at the
 * bottom), the bars' heights measured into contentPadding. Blur (Haze/RenderEffect) does not render in
 * @Preview — on a device the bars are frosted, here only the tint. The composer is the REAL [ChatInput]
 * (not a stub): its rememberLauncherForActivityResult is unblocked by the fake
 * [PreviewActivityResultRegistryOwner].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPreviewScaffold(
    title: String,
    messages: List<Message>,
    interactiveCards: Boolean = false,
    streamingText: String? = null,
    streamingWaiting: Boolean = false,
    stats: ResponseStats? = null,
    error: String? = null,
    showInput: Boolean = true,
) {
    MyApplicationTheme(mode = ThemeMode.DARK) {
        val glassState = rememberGlassState()
        // The same measurement scheme as in the production ChatScreen: the floating bars' heights → the feed's
        // contentPadding, so the edge messages stop at the bar's edge instead of hiding under the glass.
        val density = LocalDensity.current
        var topBarHeightPx by remember { mutableStateOf(0) }
        var bottomBarHeightPx by remember { mutableStateOf(0) }
        val topPad = with(density) { topBarHeightPx.toDp() }
        val bottomPad = with(density) { bottomBarHeightPx.toDp() }

        Box(modifier = Modifier.fillMaxSize()) {
            // The aurora is a SEPARATE source layer for the glass (as in production): the bars blur it.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .auroraBackground()
                    .glassSource(glassState),
            )
            Box(modifier = Modifier.fillMaxSize()) {
                // The feed across the whole screen: it scrolls UNDER the floating bars (messages are visible behind the glass).
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .glassSource(glassState),
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(message = m, interactiveCards = interactiveCards)
                    }
                    if (streamingText != null || streamingWaiting) {
                        item(key = "streaming") {
                            StreamingBubble(
                                text = streamingText.orEmpty(),
                                showCursor = !streamingText.isNullOrEmpty(),
                                isWaiting = streamingWaiting,
                            )
                        }
                    }
                }

                // The TOP floating bar: the header's glass "pill" + the stat bar.
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { topBarHeightPx = it.height },
                ) {
                    val barShape = RoundedCornerShape(24.dp)
                    GlassSurface(
                        state = glassState,
                        shape = barShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp, bottom = 8.dp)
                            .shadow(16.dp, barShape, clip = false),
                    ) {
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
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Назад")
                                }
                            },
                            actions = {
                                IconButton(onClick = {}) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Поиск по чату",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }
                    if (stats != null) {
                        ResponseStatsBar(
                            stats = stats,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                // The BOTTOM floating bar: error + modes + the composer (a visual stub).
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { bottomBarHeightPx = it.height }
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                ) {
                    // The REAL ChatErrorLine, not a copy of it. It used to be re-implemented here as a bare
                    // Row — which is why no baseline ever showed that the production banner had no surface
                    // under it and let the feed read through the red text.
                    if (error != null) {
                        ChatErrorLine(text = error, onRetry = {}, glassState = glassState)
                    }
                    if (showInput) {
                        ModeSelector(
                            selected = AgentMode.CHAT,
                            enabled = true,
                            available = listOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT),
                            onSelect = {},
                        )
                        // The REAL ChatInput, not a stub — a preview must show the production component.
                        // The provider swaps the ActivityResultRegistryOwner, otherwise rememberLauncherForActivityResult crashes.
                        CompositionLocalProvider(
                            LocalActivityResultRegistryOwner provides PreviewActivityResultRegistryOwner,
                        ) {
                            ChatInput(
                                value = "",
                                onValueChange = {},
                                onSend = {},
                                onCancel = {},
                                isLoading = false,
                                glassState = glassState,
                                onExpand = {},
                                voiceState = VoiceInputState.Idle,
                                onVoiceToggle = {},
                                onPermissionDenied = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

internal val previewChatMessages = listOf(
    previewMessage("user", "Привет! Помоги составить план на неделю."),
    previewMessage(
        "assistant",
        "Конечно. Вот черновик:\n\n- **Пн** — планирование\n- **Вт** — глубокая работа\n- **Ср** — встречи\n\nПоправить под тебя?",
    ),
    previewMessage("user", "Добавь спорт по утрам."),
    previewMessage("assistant", "Готово — добавил утренние тренировки во все будни. Ещё что-нибудь?"),
)

/** Lesson steps with interactive cards — shared by the @Preview and the screenshot test. */
internal val previewLessonMessages = listOf(
    previewMessage("assistant", "Разберём, из чего состоит твой запрос к модели."),
    previewMessage(
        "assistant", "",
        cardSpec = """{"template":"concept","params":{"term":"Промпт","definition":"Твой запрос к модели — то, что ты пишешь в чат.","example":"«составь план на неделю»"}}""",
    ),
    previewMessage(
        "assistant", "",
        cardSpec = """{"template":"reveal","params":{"question":"Что модель видит перед твоим сообщением?","answer":"Препромпт — скрытую инструкцию: правила и выбранную персону."}}""",
    ),
    previewMessage(
        "assistant", "",
        cardSpec = """{"template":"lesson_quiz","params":{"questions":[{"q":"Что такое промпт?","options":["Твой запрос к модели","Список всех файлов","Ничего не отправляется"],"correct":0,"explain":"Промпт — это то, что ты пишешь модели."}]}}""",
    ),
)

@Preview(name = "Чат — переписка", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 760)
@Composable
private fun ChatScreenConversationPreview() {
    ChatPreviewScaffold(
        title = "Чат",
        messages = previewChatMessages,
        stats = ResponseStats(responseTimeMs = 1840, completionTokens = 128, contextTokensUsed = 3200),
    )
}

@Preview(name = "Чат — идёт ответ", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 760)
@Composable
private fun ChatScreenStreamingPreview() {
    ChatPreviewScaffold(
        title = "Чат",
        messages = listOf(previewMessage("user", "Расскажи про токены простыми словами.")),
        streamingText = "Токен — это кусочек текста: слово или часть слова. Модель читает и пишет",
    )
}

@Preview(name = "Чат — ждём ответ", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 760)
@Composable
private fun ChatScreenWaitingPreview() {
    ChatPreviewScaffold(
        title = "Чат",
        messages = listOf(previewMessage("user", "Сколько будет 2+2?")),
        streamingWaiting = true,
    )
}

@Preview(name = "Чат — ошибка/повтор", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 760)
@Composable
private fun ChatScreenErrorPreview() {
    ChatPreviewScaffold(
        title = "Чат",
        messages = listOf(previewMessage("user", "Проверь погоду на завтра.")),
        error = "Модель вернула пустой ответ — попробуй ещё раз.",
    )
}

@Preview(name = "Урок — интерактивные карточки", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 940)
@Composable
private fun ChatScreenLessonPreview() {
    ChatPreviewScaffold(
        title = "Как ИИ читает твой запрос",
        interactiveCards = true,
        showInput = false,
        messages = previewLessonMessages,
    )
}

@Preview(name = "Чат — пустой", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 760)
@Composable
private fun ChatScreenEmptyPreview() {
    ChatPreviewScaffold(title = "Новый чат", messages = emptyList())
}

/**
 * A fake [ActivityResultRegistryOwner] for the @Preview of the real [ChatInput]: it calls
 * rememberLauncherForActivityResult (the RECORD_AUDIO permission), and without an owner it crashes in a
 * preview. onLaunch is a no-op: nobody taps the button in a preview, registering the launcher is enough
 * not to crash.
 */
private val PreviewActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                // a no-op in previews
            }
        }
}

/**
 * The preview body of the REAL [ChatInput] in three states: empty, with text and "answering" (the "stop"
 * button). The permission launcher is swapped by [PreviewActivityResultRegistryOwner], otherwise the
 * @Preview/screenshot test crashes. Blur (Haze/RenderEffect) does not render in previews — the bar is
 * frosted only on a device. `internal` so the Roborazzi test can call it too.
 */
@Composable
internal fun ChatInputStatesPreview() {
    MyApplicationTheme(mode = ThemeMode.DARK) {
        val glassState = rememberGlassState()
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides PreviewActivityResultRegistryOwner) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .auroraBackground()
                    .glassSource(glassState)
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                // Empty — the placeholder, the send button is dimmed.
                ChatInput(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onCancel = {},
                    isLoading = false,
                    glassState = glassState,
                    onExpand = {},
                    voiceState = VoiceInputState.Idle,
                    onVoiceToggle = {},
                    onPermissionDenied = {},
                )
                // With text — sending is active + "clear".
                ChatInput(
                    value = "Составь план на неделю с утренним спортом",
                    onValueChange = {},
                    onSend = {},
                    onCancel = {},
                    isLoading = false,
                    glassState = glassState,
                    onExpand = {},
                    voiceState = VoiceInputState.Idle,
                    onVoiceToggle = {},
                    onPermissionDenied = {},
                )
                // Answering — the "stop" button with a progress ring.
                ChatInput(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onCancel = {},
                    isLoading = true,
                    glassState = glassState,
                    onExpand = {},
                    voiceState = VoiceInputState.Idle,
                    onVoiceToggle = {},
                    onPermissionDenied = {},
                )
            }
        }
    }
}

@Preview(name = "Композер чата — состояния", showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 640)
@Composable
private fun ChatInputPreview() {
    ChatInputStatesPreview()
}

