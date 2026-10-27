package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.AgentSourceContext
import com.aiopenpocket.ui.agents.builder.components.riskClassLabel
import com.aiopenpocket.ui.agents.builder.components.riskColor
import com.aiopenpocket.ui.chat.components.ChatInput
import com.aiopenpocket.ui.chat.components.ChatSearchBar
import com.aiopenpocket.ui.chat.components.FullScreenComposer
import com.aiopenpocket.ui.chat.components.LessonAskBar
import com.aiopenpocket.ui.chat.components.LessonControlBar
import com.aiopenpocket.ui.chat.components.MessageBubble
import com.aiopenpocket.ui.chat.components.MicButton
import com.aiopenpocket.ui.chat.components.ModeSelector
import com.aiopenpocket.ui.chat.components.availableModesFor
import com.aiopenpocket.ui.chat.components.ResponseStatsBar
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.chat.components.ToolTracePanel
import com.aiopenpocket.ui.chat.components.VoiceRecordingBar
import com.aiopenpocket.ui.components.MarkdownText
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.plan.PlanCard
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.aiopenpocket.voice.input.VoiceInputState
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    // A tap on the agent chat's header (Task 21) opens the full-screen AgentDetailScreen
    // (com.aiopenpocket.ui.agents.detail.AgentDetailRoute, route "agent/{agentId}") — it replaces the
    // old read-only inspector sheet that used to live right in this screen.
    onOpenAgentDetail: (String) -> Unit = {},
    // The CTA on the Academy capstone completion card (Task 23, "Open the builder"): opens agent_builder
    // with a new empty draft marked sourceContext = ACADEMY_CAPSTONE. The button exists only on the REAL
    // agent_blueprint completion card (see DrivenUiEngine/LessonChatController.postCompletion) —
    // ordinary lessons do not show it.
    onOpenAgentBuilder: (AgentSourceContext) -> Unit = {},
    // A tap on "Open →" on the live plan card (Task 12, structured-plan-engine-907066) opens
    // com.aiopenpocket.ui.plan.PlanDetailRoute, route "plan/{planId}".
    onOpenPlanDetail: (String) -> Unit = {},
) {
    val glassState = rememberGlassState()
    val viewModel: ChatViewModel = metroViewModel()
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The heights of the floating glass bars (header/composer) are measured via onSizeChanged in the layout
    // below and turned into the feed's contentPadding (so messages scroll UNDER the bars, while the edge
    // ones do not hide under the glass) and into the FAB offset. Kept at function level so both
    // BoxWithConstraints and the FAB (outside it) read bottomPad.
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val topPad = with(density) { topBarHeightPx.toDp() }
    val bottomPad = with(density) { bottomBarHeightPx.toDp() }

    LaunchedEffect(conversationId) {
        viewModel.setConversationId(conversationId)
    }

    // Actions of switch cards. The template is retired (RETIRED_CARD_TEMPLATES): the model cannot create new
    // switches, and old messages with one render as they were — the toggle in them does nothing.
    // Stable feed callbacks: they do not depend on a particular message, so they are created once
    // (remember). Unstable lambdas (new on every recomposition) would make visible bubbles rebuild for
    // nothing — Message is a stable data class, and with stable callbacks Compose skips unchanged items.
    val onCardAction: (String, Boolean) -> Unit = remember { { _, _ -> } }
    val onChoiceSelected: (String) -> Unit = remember { { text: String -> viewModel.sendUserMessage(text) } }
    val onLessonQuizComplete: () -> Unit = remember { { viewModel.handleIntent(ChatIntent.CompleteLessonQuiz) } }
    val onPersonalizeImage: (DrivenUiCard.ImageCard) -> Unit = remember {
        { card -> viewModel.handleIntent(ChatIntent.PersonalizeLessonImage(card.prompt, card.caption)) }
    }
    val onLessonVideo: (String, android.net.Uri) -> Unit = remember {
        { prompt, uri -> viewModel.handleIntent(ChatIntent.AnalyzeLessonVideo(prompt, uri)) }
    }
    val onLessonSegment: (String, String, android.net.Uri) -> Unit = remember {
        { cardPrompt, concept, uri -> viewModel.handleIntent(ChatIntent.SegmentLessonPhoto(cardPrompt, concept, uri)) }
    }
    // The live reminder card: the heartbeat subscription + cancel are stable references to ViewModel methods.
    val observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = remember { viewModel::observeHeartbeat }
    val onCancelHeartbeat: (String) -> Unit = remember { viewModel::cancelHeartbeat }
    // The live plan card (Task 12): PlanCard subscribes to the PlanSnapshot by id itself — see its kdoc.
    val observePlan: (String) -> Flow<PlanSnapshot?> = remember { viewModel::observePlan }
    var composerExpanded by remember { mutableStateOf(false) }

    // --- Full-text search over the chat's messages (the magnifier icon in the toolbar). ---
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }

    // Closing the search — a shared path for the cross, the system "back" and a conversation change: besides
    // the local flag, it releases the ViewModel's full history window (see setSearchActive below).
    fun closeSearch() {
        searchActive = false
        searchQuery = ""
        viewModel.setSearchActive(false)
    }
    // Matches are counted by the ViewModel (debounce + the Default dispatcher) — here we only read them.
    val matchIndices = state.searchMatches
    LaunchedEffect(matchIndices.size) {
        if (currentMatch >= matchIndices.size) currentMatch = 0
    }
    val currentMatchId = matchIndices.getOrNull(currentMatch)
        ?.let { state.messages.getOrNull(it)?.id }

    // The system "back" with the search open closes the search, not the chat.
    BackHandler(enabled = searchActive) { closeSearch() }

    val listState = rememberLazyListState()
    val hasStreamingItem = state.isLoading

    val inputVisible = !state.lessonMode && !searchActive
    val showScrollDown by remember {
        derivedStateOf { listState.canScrollForward }
    }

    // The reserve for the answer: empty space the height of the VISIBLE viewport, so the question sticks to
    // the top edge and the answer grows into it from top to bottom.
    //
    // The height is ANIMATED, but only one way — that is essential. Collapsing is smooth (otherwise a
    // screen-sized hole vanished within a frame, which read as a jerk at the end of the turn), while GROWTH
    // is instant: the reserve is needed by the `animateScrollToItem(qIndex, 0)` scroll at the very moment
    // it starts. If the space filled in over the same 280 ms, the scroll would chase growing content and
    // could fail to bring the question to the top edge — exactly what the reserve exists for.
    val fullHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tailSpacerHeight by animateDpAsState(
        targetValue = if (hasStreamingItem) (fullHeight - topPad - bottomPad).coerceAtLeast(0.dp) else 0.dp,
        animationSpec = if (hasStreamingItem) snap() else tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "tail_spacer_collapse",
    )

    /**
     * Index of the feed's last item: messages + the streaming bubble + the tail spacer.
     *
     * The spacer is counted by ITS OWN height, not by [hasStreamingItem]: it outlives the flag while it
     * collapses, and during that window the index would be one less than the real one. Symptom: you pulled
     * the feed to the bottom during the stream, the turn ended — and scrolling "to the last item" went into
     * the not-yet-collapsed emptiness.
     */
    fun lastItemIndex(): Int {
        val streamingItems = if (hasStreamingItem) 1 else 0
        val spacerItems = if (tailSpacerHeight > 0.dp) 1 else 0
        return state.messages.size - 1 + streamingItems + spacerItems
    }

    // The pagination gate: when the chat opens we stand at index 0 for a split second (before the
    // autoscroll to the bottom) — without this flag "load older" would fire right on opening. It is
    // allowed only AFTER the first autoscroll to the bottom has run. Reset when the conversation changes.
    var initialScrollDone by remember(conversationId) { mutableStateOf(false) }

    // --- Sticking to the bottom (pinnedToBottom) is the single source of truth for ALL autoscrolls. ---
    // We unstick only on a user GESTURE (the start of a drag): "at the bottom?" used to be checked as "the
    // last item is at least partly visible", and while a long answer was streaming (a bubble taller than
    // the screen) any attempt to scroll up inside the bubble was overridden by the next token — the user
    // was locked at the bottom until the stream ended. We stick back when the feed stops at the very bottom.
    var pinnedToBottom by remember(conversationId) { mutableStateOf(true) }
    // "Something new arrived while we read the history" — the FAB is highlighted instead of a forced jump down.
    var hasUnseenNew by remember(conversationId) { mutableStateOf(false) }
    // A ChatGPT/Claude-style streaming turn: when a question is sent it moves to the TOP of the screen, empty
    // space is reserved below (a tail spacer in the feed), and the answer "types" into it from top to
    // bottom — the feed does not jerk with every token, and no scrolling is needed after the answer. The
    // flag is active from sending the question to the end of the turn; reset by a user gesture (the user
    // took control) and by a conversation change.
    var streamAnchorActive by remember(conversationId) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                pinnedToBottom = false
                streamAnchorActive = false
            }
        }
    }
    // Stuck back: the feed is still and scrolled to the last pixel (including the end of a fling).
    // Programmatic scrolls do not emit DragInteraction, so an autoscroll does not unstick itself.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollFwd) ->
                if (!scrolling && !canScrollFwd && listState.layoutInfo.totalItemsCount > 0) {
                    pinnedToBottom = true
                    hasUnseenNew = false
                }
            }
    }
    // Leaving the search: we may have stayed in the middle of the history at a found match — stickiness is
    // recomputed from the actual position, so the next answer does not yank the reader down.
    LaunchedEffect(searchActive) {
        if (!searchActive) pinnedToBottom = !listState.canScrollForward
    }

    // A new message, or the start/end of a stream. The key is the id of the LAST message (not the list size):
    // loading older messages grows the feed at the top without changing the last one — no need to touch
    // the bottom.
    LaunchedEffect(state.messages.lastOrNull()?.id, hasStreamingItem) {
        if (searchActive) return@LaunchedEffect // during a search we do not interrupt the scroll to a match
        val last = state.messages.lastOrNull()
        when {
            // THE START OF A TURN: the user sent a question, the stream started. Pin the question to the top of the
            // screen — the answer will "type" below, into the reserved tail spacer (see LazyColumn),
            // instead of pushing the feed from below. scrollToItem(qIndex, 0): the question exactly at the
            // top edge.
            last?.role == "user" && hasStreamingItem -> {
                streamAnchorActive = true
                val qIndex = state.messages.lastIndex
                // Smoothly, not with a jerk: the question used to jump to the top edge INSTANTLY, and that read
                // as the screen twitching. Only the very first scroll on opening a conversation stays
                // instant (initialScrollDone is still false) — there the feed is hidden with alpha=0, and an
                // animation would show a scroll through the whole history.
                if (qIndex >= 0) {
                    if (initialScrollDone) {
                        listState.animateScrollToItem(qIndex, 0)
                    } else {
                        listState.scrollToItem(qIndex, 0)
                    }
                }
            }
            // THE END OF A TURN: the answer is saved, the stream bubble is gone. Do NOT pull the feed down — leave
            // the answer where it is being read; stickiness is recomputed from the actual position.
            streamAnchorActive && !hasStreamingItem -> {
                streamAnchorActive = false
                pinnedToBottom = !listState.canScrollForward
            }
            // The normal path (not a streaming turn: a lesson posts a message, something else arrives etc.): go to
            // the bottom only when stuck there; otherwise mark "there is something new" (FAB highlight).
            pinnedToBottom -> {
                // scrollOffset = Int.MAX_VALUE scrolls to the VERY bottom: a plain scrollToItem(target) aligns the top
                // of the item with the top of the viewport, and a long answer stayed cut off at the bottom.
                val target = lastItemIndex()
                if (target >= 0) {
                    // Smoothly too — except the first scroll (see above).
                    if (initialScrollDone) {
                        listState.animateScrollToItem(target, Int.MAX_VALUE)
                    } else {
                        listState.scrollToItem(target, Int.MAX_VALUE)
                    }
                }
            }

            state.messages.isNotEmpty() -> hasUnseenNew = true
        }
        if (state.messages.isNotEmpty()) initialScrollDone = true
    }

    // Search: when the current match changes, scroll to it.
    LaunchedEffect(currentMatch, matchIndices) {
        matchIndices.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it) }
    }

    // Pagination: near the top of the feed we load earlier messages and anchor the position so the content
    // does not "jump" when the window grows at the top. pendingAnchorId is the item we keep in place.
    //
    // IMPORTANT: loading is triggered only on a LIVE scroll (isScrollInProgress), not on any recomposition
    // near the top. The condition used to rest on derivedStateOf(firstVisibleItemIndex) + a
    // LaunchedEffect with state.messages in its keys — after EVERY load the list re-emitted, the key
    // changed, the user was still at the top (we had just loaded things there), and the effect immediately
    // started the next load — a recursive storm of relayouts right under the finger. From the outside it
    // looked like "scrolling does not work": the feed kept rebuilding instead of scrolling normally.
    var pendingAnchorId by remember { mutableStateOf<String?>(null) }
    // The feed size at the moment the load was requested: it tells our load apart from an unrelated re-emit
    // of the same window (a touched updatedAt, a background annotation).
    var anchorRequestSize by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collect { (index, scrolling) ->
                if (scrolling && index <= 2 && initialScrollDone && state.hasMoreOlder &&
                    pendingAnchorId == null && !searchActive
                ) {
                    anchorRequestSize = state.messages.size
                    pendingAnchorId = state.messages.getOrNull(index)?.id
                    viewModel.loadOlderMessages()
                }
            }
    }
    LaunchedEffect(state.messages) {
        val anchor = pendingAnchorId ?: return@LaunchedEffect
        // An unrelated re-emit of the same window (the size did not grow, the anchor is in place, older messages
        // remain) is NOT our load: keep the anchor, or it would be reset before the prefix arrived and the
        // feed would "eat" the load gesture. Wait for the emit where the window really grew at the top (or
        // we reached the very oldest).
        if (state.messages.size == anchorRequestSize &&
            state.hasMoreOlder &&
            state.messages.any { it.id == anchor }
        ) {
            return@LaunchedEffect
        }
        val newIndex = state.messages.indexOfFirst { it.id == anchor }
        // The older messages stuck on at the top — instantly (no animation) put the anchor back where it was.
        if (newIndex > listState.firstVisibleItemIndex) {
            listState.scrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
        }
        pendingAnchorId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The aurora is a SEPARATE source layer for the glass: it is what the bars and the navbar blur. The source
        // used to be only the feed (glassSource on LazyColumn), and behind a bar — where there is no feed —
        // hazeEffect had nothing to sample and fell back to a flat MaterialTheme.colorScheme.background+tint
        // (that very "murky slab"). The source must be a separate layer: a modifier on the root Box would
        // capture the bars themselves too (a loop), so the aurora is moved into its own matchParentSize
        // layer behind.
        Box(
            modifier = Modifier
                .matchParentSize()
                .auroraBackground()
                .glassSource(glassState),
        )
        // The glass bars "float" ON TOP of the feed (iOS style): the feed fills the whole Box and scrolls UNDER
        // the header and the composer, so behind the glass the messages themselves are visible and blurred,
        // not only the aurora. The bars' heights are measured (onSizeChanged) → the feed's contentPadding,
        // so the edge messages stop at the bar's edge rather than hide under it.
        Box(modifier = Modifier.fillMaxSize()) {
            // fullHeight/tailSpacerHeight are lifted to the scope above: lastItemIndex() reads them too, and it
            // needs to know whether the spacer is alive right now. The height comes from LocalWindowInfo,
            // NOT from BoxWithConstraints: that one is a SubcomposeLayout and subcomposed the whole screen
            // on every recomposition of the body (expensive while typing).

            // THE FEED (the back layer) fills the whole Box and scrolls under both bars. Fresh messages at the
            // bottom. Arrangement.Bottom: a short chat sticks to the bottom. The top/bottom contentPadding =
            // the measured heights of the bars, so the first/last message does not slide under the glass.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // The feed is not shown until the first autoscroll: LazyColumn is born at index 0 (the old
                    // messages), and before the snap the user saw a flash of the start of the history.
                    // graphicsLayer reads the flag in the draw phase — the change does not recompose the feed.
                    .graphicsLayer { alpha = if (initialScrollDone) 1f else 0f }
                    // The source for the glass chrome (top bar/input) — the feed itself is NOT blurred, it is only read
                    // as a backdrop by someone else's hazeEffect (perf: no RenderEffect in the items).
                    .glassSource(glassState),
                contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                verticalArrangement = Arrangement.Bottom,
            ) {
                items(
                    state.messages,
                    key = { it.id },
                    // A hint for Compose to reuse the layout of a bubble of the same type while scrolling — fewer rebuilds
                    // and stutters on a long feed.
                    contentType = { it.role },
                ) { message ->
                    // Per-item callbacks are remembered by id (and by the needed flags) so they are stable between
                    // recompositions — then Compose skips unchanged bubbles.
                    val onDelete = remember(message.id) {
                        { viewModel.handleIntent(ChatIntent.DeleteMessage(message.id)) }
                    }
                    val onLessonComplete = remember(message.id, state.lessonMode) {
                        {
                            // A JSON lesson: mark it done and return to the list. The old roadmap lessons with a separate
                            // quiz screen are gone — all lessons are JSON with their own quiz.
                            if (state.lessonMode) viewModel.completeLesson(onNavigateBack) else onNavigateBack()
                        }
                    }
                    // The capstone CTA (Task 23): rendered ONLY when the card itself carries primaryAction (see
                    // DrivenUiEngine.LessonCompleteCardView) — that is the capstone-only gate; here we just
                    // open the builder with the right source.
                    val onOpenAgentBuilderTap = remember(message.id) {
                        { onOpenAgentBuilder(AgentSourceContext.ACADEMY_CAPSTONE) }
                    }
                    MessageBubble(
                        message = message,
                        onDelete = onDelete,
                        interactiveCards = state.isTutorial,
                        highlighted = message.id == currentMatchId,
                        fontScale = state.chatFontScale.scale,
                        onChoiceSelected = onChoiceSelected,
                        onFormSubmitted = onChoiceSelected,
                        onLessonQuizComplete = onLessonQuizComplete,
                        onLessonComplete = onLessonComplete,
                        onOpenAgentBuilder = onOpenAgentBuilderTap,
                        onCardAction = onCardAction,
                        observeHeartbeat = observeHeartbeat,
                        onCancelHeartbeat = onCancelHeartbeat,
                        personalizingImages = state.personalizingImagePrompts,
                        onPersonalizeImage = onPersonalizeImage,
                        analyzingVideos = state.analyzingVideoPrompts,
                        onLessonVideo = onLessonVideo,
                        segmentingPrompts = state.segmentingPrompts,
                        onLessonSegment = onLessonSegment,
                    )
                }
                if (hasStreamingItem) {
                    item(key = "streaming") {
                        // The subscription to the streaming text is LOCAL to this item: token deltas recompose only the
                        // bubble, not the whole screen.
                        val streamingText by viewModel.streamingText.collectAsState()
                        StreamingBubble(
                            text = streamingText,
                            showCursor = streamingText.isNotEmpty(),
                            isWaiting = streamingText.isEmpty(),
                            fontScale = state.chatFontScale.scale,
                        )
                    }
                }
                // The reserve for the answer: empty space the height of the VISIBLE viewport (the screen minus both
                // bars), so the question sticks to the top edge of the visible area and the answer grows into
                // it from top to bottom. The condition is NOT on hasStreamingItem but on the height itself:
                // after the turn ends the spacer is still alive while its animation collapses it (see
                // tailSpacerHeight above).
                if (tailSpacerHeight > 0.dp) {
                    item(key = "tail_spacer") {
                        Spacer(Modifier.height(tailSpacerHeight))
                    }
                }
            }

            // THE TOP overlay: the header's glass "pill" + the stat bar, under the status bar.
            // The height is measured (onSizeChanged → topBarHeightPx) → the feed's contentPadding.top.
            // statusBarsPadding lives on the pill itself (a child of the Column), so the measured height
            // already includes its inset.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeightPx = it.height },
            ) {
                // The header's floating glass "pill" — like the bottom navbar: rounded, with side paddings, a soft
                // shadow and a rim border (from GlassSurface). The status bar inset is moved onto the pill
                // itself (statusBarsPadding), so the children (TopAppBar/the search row) do NOT add their own
                // inset — otherwise it would double and the pill would go under the system clock/cutout.
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
                    Column {
                        if (searchActive) {
                            ChatSearchBar(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    currentMatch = 0
                                    viewModel.setSearchQuery(it)
                                },
                                matchCount = matchIndices.size,
                                currentMatch = if (matchIndices.isEmpty()) 0 else currentMatch + 1,
                                onPrev = {
                                    if (matchIndices.isNotEmpty()) currentMatch = (currentMatch - 1 + matchIndices.size) % matchIndices.size
                                },
                                onNext = { if (matchIndices.isNotEmpty()) currentMatch = (currentMatch + 1) % matchIndices.size },
                                onClose = { closeSearch() },
                                containerColor = Color.Transparent,
                            )
                        } else {
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
                                            text = state.title ?: tr("Чат", "Chat"),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = onNavigateBack) {
                                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = tr("Назад", "Back"))
                                    }
                                },
                                actions = {
                                    // Search over the chat's messages.
                                    IconButton(onClick = { searchActive = true; viewModel.setSearchActive(true) }) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = tr("Поиск по чату", "Search chat"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // The tool trace toggle is shown ONLY when there is a trace — otherwise the button would be
                                    // "dead" (it used to be a gear with an empty menu).
                                    if (state.toolTrace.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.handleIntent(ChatIntent.ToggleToolTrace) }) {
                                            Icon(
                                                Icons.Default.Build,
                                                contentDescription = if (state.showToolTrace) tr("Скрыть инструменты", "Hide tools") else tr("Показать инструменты", "Show tools"),
                                                tint = if (state.showToolTrace) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    // A tap opens the full-screen AgentDetailScreen (Task 21). Only for agent chats.
                                    if (state.agentId != null) {
                                        IconButton(onClick = { onOpenAgentDetail(state.agentId!!) }) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = tr("Открыть настройки агента", "Open agent settings"),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }

                // Context/tokens — a thin progress bar under the header. Shown while there are statistics (including
                // during loading — then the previous turn's context is visible).
                if (state.lastResponseStats != null) {
                    ResponseStatsBar(
                        stats = state.lastResponseStats!!,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // THE BOTTOM overlay: the composer and all bottom bars. The height is measured (onSizeChanged →
            // bottomBarHeightPx) → the feed's contentPadding.bottom and the FAB offset. onSizeChanged comes
            // BEFORE windowInsetsPadding so the measured height INCLUDES the bottom inset (navbar/IME):
            // safeDrawing bottom = max(navbar, keyboard), without counting twice — navigationBars/
            // imePadding are NOT added on top.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {

                if (state.showToolTrace && state.toolTrace.isNotEmpty()) {
                    ToolTracePanel(
                        traces = state.toolTrace,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                if (state.error != null) {
                    ChatErrorLine(
                        text = state.error!!,
                        onRetry = if (state.canRetry) ({ viewModel.handleIntent(ChatIntent.RetryRequest) }) else null,
                    )
                }
                // The lesson step's own problem (no Meta key, an unreadable clip): a line of its own, with no "Retry"
                // (the step's card has its own buttons), and it leaves with the step, not with the turn's error.
                state.lessonStepProblem?.let { ChatErrorLine(text = it, onRetry = null) }

                // A recognition error is a soft self-dismissing hint, not a hanging error banner.
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

                // The tool-approval bar (backend — Task 9, UI — Task 21): a BEFORE_TOOL hook required confirmation, and
                // the turn is paused waiting for the decision. Unlike PlanCard/ModeSelector below it is
                // deliberately NOT gated on state.agentId == null: confirming a tool is exactly what custom
                // agents' hooks trigger (Tasks 8/9).
                state.pendingToolApproval?.let { request ->
                    ToolApprovalBar(
                        request = request,
                        onApprove = { viewModel.handleIntent(ChatIntent.ApproveToolCall) },
                        onDeny = { viewModel.handleIntent(ChatIntent.DenyToolCall) },
                    )
                }

                // The live plan card (Task 12) — above the input field. It shows the proposal preview (Approve/Ask for
                // changes) while pendingPlanApproval is not null, otherwise compact progress while
                // activePlanId (ACTIVE/PAUSED) is not null; it subscribes to the full PlanSnapshot by id
                // itself through viewModel.observePlan — see the PlanCard.kt kdoc. Actions go directly as
                // typed ChatIntent commands to PlanRepository/PlanCommands (Task 5); no second approval
                // mechanism is started here.
                // (cluster A) A plan belongs to an agent: the plan card is shown in chats bound to a
                // mini-agent (agentId != null) — the planner does not work without an agent, and a plain
                // chat no longer has plan modes at all (see availableModesFor below).
                val livePlanId = state.pendingPlanApproval?.planId ?: state.activePlanId
                if (livePlanId != null && !state.lessonMode && !state.isTutorial && state.agentId != null) {
                    PlanCard(
                        planId = livePlanId,
                        observePlan = observePlan,
                        onApprove = { planId, version -> viewModel.handleIntent(ChatIntent.ApprovePlan(planId, version)) },
                        onRequestChanges = { planId, version, feedback ->
                            viewModel.handleIntent(ChatIntent.RequestPlanChanges(planId, version, feedback))
                        },
                        onPause = { planId -> viewModel.handleIntent(ChatIntent.PausePlan(planId)) },
                        onResume = { planId -> viewModel.handleIntent(ChatIntent.ResumePlan(planId)) },
                        onCancel = { planId -> viewModel.handleIntent(ChatIntent.CancelPlan(planId)) },
                        onConfirmStep = { planId, stepId -> viewModel.handleIntent(ChatIntent.ConfirmPlanStep(planId, stepId)) },
                        onOpenDetail = onOpenPlanDetail,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                // The input field is at the bottom, as in a normal chat. While answering it shows the "stop" button.
                // In lesson mode no input is needed — the controller drives the steps itself (the "Next"
                // button at the bottom of the screen).
                AnimatedVisibility(visible = inputVisible) {
                    Column {
                        // (3-agents, cluster A) Plan modes belong to agents: PLAN/GOAL require an agent that resolves
                        // (the planner fails without one), so a plain chat gets only Chat/Reflection, while a
                        // chat bound to a mini-agent gets the full set. The selector is hidden in guided lessons.
                        if (!state.isTutorial) {
                            // The selector used to sit WITHOUT a backing right on top of the feed: messages showed through
                            // "Chat"/"Reflection", and the text mixed with the chips until it was unreadable.
                            // It gets the same slab as the rest of the bottom chrome.
                            val modeShape = RoundedCornerShape(24.dp)
                            GlassSurface(
                                state = glassState,
                                shape = modeShape,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                            ) {
                                ModeSelector(
                                    selected = state.agentMode,
                                    enabled = !state.isLoading,
                                    available = availableModesFor(state.agent),
                                    onSelect = { viewModel.handleIntent(ChatIntent.SetMode(it)) },
                                )
                            }
                        }
                        // While recording, the input field is replaced by a recording panel (as in Telegram/Claude): cancel on
                        // the left, status/timer/waveform/live text in the middle, finish on the right. The
                        // result goes into the field.
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
                                // inputText comes from a separate flow and is read in a narrow scope: typing recomposes only
                                // the composer, not the whole chat body (feed/overlays).
                                val inputText by viewModel.inputText.collectAsState()
                                ChatInput(
                                    value = inputText,
                                    onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
                                    onSend = { viewModel.handleIntent(ChatIntent.SendMessage) },
                                    onCancel = { viewModel.handleIntent(ChatIntent.CancelRequest) },
                                    isLoading = state.isLoading,
                                    glassState = glassState,
                                    placeholder = when (state.agentMode) {
                                        AgentMode.PLAN -> tr("Опиши задачу — агент составит план…", "Describe the task — the agent will draft a plan…")
                                        // GOAL takes the text only while the conversation has no plan (then it is a task for the
                                        // planner). With a plan, its step runs and the message is ignored
                                        // (StructuredPlanOrchestrator.executeGoal), so "state your goal"
                                        // was misleading here.
                                        AgentMode.GOAL -> when {
                                            state.pendingPlanApproval != null -> tr("Сначала одобри план в карточке выше…", "Approve the plan in the card above first…")
                                            state.activePlanId != null -> tr("Напиши «дальше» — агент сделает следующий шаг плана…", "Type “next” — the agent will do the next plan step…")
                                            else -> tr("Напиши цель — сначала придёт план…", "Write a goal — a plan comes first…")
                                        }
                                        AgentMode.REFLECT -> tr("Спроси — агент даст черновик, критику и итог…", "Ask — the agent drafts, critiques and finalizes…")
                                        // TUTOR is not chosen by the user (only a one-off question in a lesson) — treated like a plain chat.
                                        AgentMode.CHAT, AgentMode.TUTOR -> tr("Сообщение...", "Message...")
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

                if (state.lessonMode) {
                    // The controller drives the lesson: the user moves through steps with a button, no text input is needed.
                    val currentStep = state.lesson?.steps?.getOrNull(state.lessonStepIndex - 1)
                    // The manual button is for steps without their own "completion": text, image and display cards
                    // (reveal/concept/example). Interactive ones (quiz/fill/sort/match/scenario) move the lesson
                    // themselves once completed; the exception is a degenerate quiz without questions, which
                    // needs the button. "video" has a button too: filming a clip is not always possible (no
                    // OpenRouter, the wrong setting), and the lesson must not get stuck on it. "segment" for the same
                    // reason, and more so: it needs a Meta key that a judge or a fresh clone may not have.
                    val displayStep = currentStep?.type in
                            setOf("text", "image", "video", "segment", "animation", "reveal", "concept", "example", "input", "challenge")
                    // !isLoading: while a tutor answer is streaming, the lesson cannot be advanced, otherwise the next step
                    // would be printed into the live turn. The button comes back when the answer arrives. For
                    // the same reason it is hidden while the model watches the video: the answer about the clip
                    // must land under its own step.
                    val needsManualAdvance = currentStep != null && !state.lessonFinished && !state.isLoading &&
                            state.analyzingVideoPrompts.isEmpty() && state.segmentingPrompts.isEmpty() &&
                            (displayStep || (currentStep.type == "quiz" && currentStep.questions.isEmpty()))
                    // "Ask" is a free question to the LLM at any point of the lesson (until it is complete). The cursor is
                    // persisted separately (lessonStepIndex), so a question does not move the steps.
                    if (!state.lessonFinished) {
                        LessonAskBar(
                            enabled = !state.isLoading,
                            onAsk = { viewModel.handleIntent(ChatIntent.AskDuringLesson(it)) },
                        )
                    }
                    if (needsManualAdvance) {
                        val isLastStep = state.lessonStepIndex >= (state.lesson?.steps?.size ?: 0)
                        LessonControlBar(
                            label = if (isLastStep) tr("Завершить урок", "Finish lesson") else tr("Дальше →", "Next →"),
                            onClick = { viewModel.handleIntent(ChatIntent.AdvanceLesson) },
                        )
                    }
                }
            }
        }

        // A floating quick-scroll-to-bottom button — when we have gone up the history. It sits exactly ABOVE the
        // composer: bottomPad = the measured height of the bottom overlay (already with the navbar/IME
        // inset) + 16dp of margin, otherwise the FAB would overlap the "Send" button. When a new message
        // arrives while reading the history, the button is highlighted with the accent (instead of the old
        // forced jump down).
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomPad + 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The FAB is hidden during a turn: the tail spacer hangs at the bottom, so canScrollForward is always
            // true (otherwise the "down" button would show on every answer and lead into the empty reserve).
            AnimatedVisibility(visible = showScrollDown && !hasStreamingItem) {
                SmallFloatingActionButton(
                    onClick = {
                        pinnedToBottom = true
                        hasUnseenNew = false
                        // The MAX_VALUE offset goes to the very bottom (see the comment at the autoscroll): without it a
                        // long last message was aligned by its top and its bottom stayed cut off.
                        scope.launch {
                            listState.animateScrollToItem(lastItemIndex().coerceAtLeast(0), Int.MAX_VALUE)
                        }
                    },
                    containerColor = if (hasUnseenNew) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = if (hasUnseenNew) tr("Вниз — есть новое сообщение", "Scroll down — new message") else tr("Вниз", "Scroll down"),
                        tint = if (hasUnseenNew) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            }
        }

        // The full-screen composer: the same state.inputText, on top of the chat. Send/Clear go through the same
        // intents as the inline input, so both modes always show the same text. Showing/hiding is animated:
        // a fade + a slight zoom so the "expansion" feels smooth.
        AnimatedVisibility(
            visible = composerExpanded,
            enter = fadeIn(animationSpec = tween(200)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(160)) +
                    scaleOut(targetScale = 0.92f, animationSpec = tween(180)),
        ) {
            val inputText by viewModel.inputText.collectAsState()
            FullScreenComposer(
                value = inputText,
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


/** A red line under the feed; with [onRetry] it also carries the "Retry" button of the failed turn. */
@Composable
private fun ChatErrorLine(text: String, onRetry: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(tr("Повторить", "Retry"))
            }
        }
    }
}
