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
    onOpenQuiz: (String) -> Unit = {},
    // Тап по «шапке» агент-чата (Task 21) открывает полноэкранный AgentDetailScreen
    // (com.aiopenpocket.ui.agents.detail.AgentDetailRoute, маршрут "agent/{agentId}") — заменяет
    // старый read-only инспектор-шторку, которая раньше жила прямо в этом экране.
    onOpenAgentDetail: (String) -> Unit = {},
    // CTA на карточке завершения капстоуна Академии (Task 23, «Собрать этого агента»): открывает
    // agent_builder с новым пустым черновиком, помеченным sourceContext = ACADEMY_CAPSTONE. Кнопка
    // существует только на РЕАЛЬНОЙ completion-карточке agent_blueprint (см. DrivenUiEngine/
    // LessonChatController.postCompletion) — обычные уроки её не показывают.
    onOpenAgentBuilder: (AgentSourceContext) -> Unit = {},
    // Тап «Открыть →» на живой карточке плана (Task 12, structured-plan-engine-907066) — открывает
    // com.aiopenpocket.ui.plan.PlanDetailRoute, маршрут "plan/{planId}".
    onOpenPlanDetail: (String) -> Unit = {},
) {
    val glassState = rememberGlassState()
    val viewModel: ChatViewModel = metroViewModel()
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Высоты плавающих стеклянных баров (шапка/композер) меряем через onSizeChanged в разметке ниже
    // и превращаем в contentPadding ленты (чтобы сообщения скроллились ПОД барами, а крайние не
    // прятались под стеклом) и в отступ FAB. Держим на уровне функции, чтобы и BoxWithConstraints,
    // и FAB (вне его) читали bottomPad.
    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val topPad = with(density) { topBarHeightPx.toDp() }
    val bottomPad = with(density) { bottomBarHeightPx.toDp() }

    LaunchedEffect(conversationId) {
        viewModel.setConversationId(conversationId)
    }

    // Точка расширения для действий switch-карточек (реальные вкл/выкл-настройки приложения,
    // напр. toggle_daily_reminder). Запрос уведомлений вынесен в самодостаточную карточку
    // notification_permission, поэтому здесь действий пока нет.
    // Стабильные колбэки ленты: не зависят от конкретного сообщения, поэтому создаём один раз (remember).
    // Нестабильные (новые на каждой рекомпозиции) лямбды заставляли бы видимые пузыри пересобираться зря —
    // Message это стабильный data class, и при стабильных колбэках Compose пропускает неизменившиеся итемы.
    val onCardAction: (String, Boolean) -> Unit = remember { { _, _ -> } }
    val onChoiceSelected: (String) -> Unit = remember { { text: String -> viewModel.sendUserMessage(text) } }
    val onLessonQuizComplete: () -> Unit = remember { { viewModel.handleIntent(ChatIntent.CompleteLessonQuiz) } }
    val onPersonalizeImage: (DrivenUiCard.ImageCard) -> Unit = remember {
        { card -> viewModel.handleIntent(ChatIntent.PersonalizeLessonImage(card.prompt, card.caption)) }
    }
    val onLessonVideo: (String, android.net.Uri) -> Unit = remember {
        { prompt, uri -> viewModel.handleIntent(ChatIntent.AnalyzeLessonVideo(prompt, uri)) }
    }
    // Живая карточка reminder: подписка на heartbeat + отмена — стабильные ссылки на методы ViewModel.
    val observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = remember { viewModel::observeHeartbeat }
    val onCancelHeartbeat: (String) -> Unit = remember { viewModel::cancelHeartbeat }
    // Живая карточка плана (Task 12): PlanCard подписывается на PlanSnapshot по id сама — см. её kdoc.
    val observePlan: (String) -> Flow<PlanSnapshot?> = remember { viewModel::observePlan }
    var composerExpanded by remember { mutableStateOf(false) }

    // --- Полнотекстовый поиск по сообщениям чата (иконка-лупа в тулбаре). ---
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }

    // Закрыть поиск — общий путь для крестика, системной «назад» и смены диалога: помимо локального
    // флага, снимает с ViewModel полное окно истории (см. setSearchActive ниже).
    fun closeSearch() {
        searchActive = false
        searchQuery = ""
        viewModel.setSearchActive(false)
    }
    // Совпадения считает ViewModel (дебаунс + Default-диспетчер) — здесь только читаем.
    val matchIndices = state.searchMatches
    LaunchedEffect(matchIndices.size) {
        if (currentMatch >= matchIndices.size) currentMatch = 0
    }
    val currentMatchId = matchIndices.getOrNull(currentMatch)
        ?.let { state.messages.getOrNull(it)?.id }

    // Системная «назад» при открытом поиске закрывает поиск, а не выходит из чата.
    BackHandler(enabled = searchActive) { closeSearch() }

    val listState = rememberLazyListState()
    val hasStreamingItem = state.isLoading

    val inputVisible = !state.lessonMode && !searchActive
    val showScrollDown by remember {
        derivedStateOf { listState.canScrollForward }
    }

    // Резерв под ответ: пустое место в высоту ВИДИМОГО вьюпорта, чтобы вопрос прибился к верхней
    // кромке, а ответ рос в него сверху вниз.
    //
    // Высота АНИМИРУЕТСЯ, но только в одну сторону — это принципиально. Схлопывание плавное (иначе
    // дыра размером с экран исчезала за кадр, и это читалось как рывок в конце хода), а вот РОСТ
    // мгновенный: резерв нужен подскроллу `animateScrollToItem(qIndex, 0)` уже в тот момент, когда
    // тот начинается. Если бы место набиралось те же 280мс, скролл гнался бы за растущим контентом и
    // мог не довести вопрос до верхней кромки — ровно то, ради чего резерв и существует.
    val fullHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tailSpacerHeight by animateDpAsState(
        targetValue = if (hasStreamingItem) (fullHeight - topPad - bottomPad).coerceAtLeast(0.dp) else 0.dp,
        animationSpec = if (hasStreamingItem) snap() else tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "tail_spacer_collapse",
    )

    /**
     * Индекс последнего элемента ленты: сообщения + стриминговый пузырь + tail-спейсер.
     *
     * Спейсер учитывается по СВОЕЙ высоте, а не по [hasStreamingItem]: он переживает флаг на время
     * схлопывания, и в это окно индекс был бы на единицу меньше реального. Симптом: дотянул ленту до
     * низа во время стрима, ход закончился — докрутка «к последнему» уезжала в ещё не схлопнувшуюся
     * пустоту.
     */
    fun lastItemIndex(): Int {
        val streamingItems = if (hasStreamingItem) 1 else 0
        val spacerItems = if (tailSpacerHeight > 0.dp) 1 else 0
        return state.messages.size - 1 + streamingItems + spacerItems
    }

    // Гейт пагинации: при открытии чата мы доли секунды стоим у индекса 0 (до автоскролла к низу) —
    // без этого флага «подгрузка старых» сработала бы прямо на открытии. Разрешаем её только ПОСЛЕ того,
    // как первый автоскролл к низу отработал. Сбрасывается при смене диалога.
    var initialScrollDone by remember(conversationId) { mutableStateOf(false) }

    // --- Прилипание к низу (pinnedToBottom) — единственный источник истины для ВСЕХ автоскроллов. ---
    // Отлипаем только на пользовательском ЖЕСТЕ (начало драга): раньше «у низа?» проверялось как
    // «последний итем хоть частично видим», и во время стрима длинного ответа (пузырь выше экрана)
    // любая попытка проскроллить вверх внутри пузыря переигрывалась следующим токеном — пользователь
    // был заперт у низа до конца стрима. Прилипаем обратно, когда лента остановилась на самом низу.
    var pinnedToBottom by remember(conversationId) { mutableStateOf(true) }
    // «Пришло новое, пока читаем историю» — подсветка FAB вместо принудительного прыжка вниз.
    var hasUnseenNew by remember(conversationId) { mutableStateOf(false) }
    // Стрим-ход в стиле ChatGPT/Claude: при отправке вопроса он уезжает к ВЕРХУ экрана, снизу
    // резервируется пустое место (tail-spacer в ленте), и ответ «печатается» в него сверху вниз —
    // лента не дёргается за каждым токеном, и после ответа не нужно скроллить. Флаг активен от
    // отправки вопроса до конца хода; сбрасывается пользовательским жестом (юзер сам взял
    // управление) и сменой диалога.
    var streamAnchorActive by remember(conversationId) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                pinnedToBottom = false
                streamAnchorActive = false
            }
        }
    }
    // Прилип обратно: лента неподвижна и докручена до последнего пикселя (включая конец инерции).
    // Программные скроллы DragInteraction не эмитят, поэтому автоскролл сам себя не отлепляет.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollFwd) ->
                if (!scrolling && !canScrollFwd && listState.layoutInfo.totalItemsCount > 0) {
                    pinnedToBottom = true
                    hasUnseenNew = false
                }
            }
    }
    // Выход из поиска: могли остаться посреди истории у найденного совпадения — прилипание
    // пересчитываем по факту, чтобы следующий ответ не дёргал читателя вниз.
    LaunchedEffect(searchActive) {
        if (!searchActive) pinnedToBottom = !listState.canScrollForward
    }

    // Новое сообщение либо старт/конец стрима. Ключ — id ПОСЛЕДНЕГО сообщения (а не размер списка):
    // подгрузка старых растит ленту сверху, последнего не меняет — и низ дёргать не нужно.
    LaunchedEffect(state.messages.lastOrNull()?.id, hasStreamingItem) {
        if (searchActive) return@LaunchedEffect // во время поиска не перебиваем подскролл к совпадению
        val last = state.messages.lastOrNull()
        when {
            // СТАРТ ХОДА: пользователь отправил вопрос, пошёл стрим. Прибиваем вопрос к верху экрана —
            // ответ будет «печататься» ниже, в зарезервированный tail-spacer (см. LazyColumn), а не
            // толкать ленту снизу. scrollToItem(qIndex, 0): вопрос ровно у верхней кромки.
            last?.role == "user" && hasStreamingItem -> {
                streamAnchorActive = true
                val qIndex = state.messages.lastIndex
                // Плавно, а не рывком: раньше вопрос МГНОВЕННО прыгал к верхней кромке, и это
                // читалось как дёрганье экрана. Мгновенным остаётся только самый первый подскролл
                // при открытии диалога (initialScrollDone ещё false) — там лента скрыта alpha=0 и
                // анимация показала бы прокрутку через всю историю.
                if (qIndex >= 0) {
                    if (initialScrollDone) {
                        listState.animateScrollToItem(qIndex, 0)
                    } else {
                        listState.scrollToItem(qIndex, 0)
                    }
                }
            }
            // КОНЕЦ ХОДА: ответ сохранён, стрим-пузырь снят. НЕ дёргаем ленту вниз — оставляем ответ
            // там, где его читают; прилипание пересчитываем по фактической позиции.
            streamAnchorActive && !hasStreamingItem -> {
                streamAnchorActive = false
                pinnedToBottom = !listState.canScrollForward
            }
            // Обычный путь (не стрим-ход: урок постит сообщение, приходит чужое и т.п.): к низу —
            // только когда прилипли; иначе метим «есть новое» (подсветка FAB).
            pinnedToBottom -> {
                // scrollOffset = Int.MAX_VALUE — докрутка до САМОГО низа: обычный scrollToItem(target)
                // выравнивает верх элемента по верху вьюпорта, и длинный ответ оставался обрезан снизу.
                val target = lastItemIndex()
                if (target >= 0) {
                    // Тоже плавно — кроме первого подскролла (см. выше).
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

    // Поиск: при смене текущего совпадения подскроллить к нему.
    LaunchedEffect(currentMatch, matchIndices) {
        matchIndices.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it) }
    }

    // Пагинация: у верха ленты подгружаем более ранние сообщения и якорим позицию, чтобы контент не
    // «прыгал» при дорастании окна сверху. pendingAnchorId — элемент, который держим на месте.
    //
    // ВАЖНО: триггерим подгрузку только на ЖИВОМ скролле (isScrollInProgress), а не на любой
    // рекомпозиции около верха. Раньше условие держалось на derivedStateOf(firstVisibleItemIndex) +
    // LaunchedEffect с state.messages в ключах — после КАЖДОЙ подгрузки список переэмитился, ключ
    // менялся, пользователь по-прежнему был у верха (только что туда подгрузили), и эффект тут же
    // запускал следующую догрузку — рекурсивный шторм релейаутов прямо под пальцем. Внешне это и
    // выглядело как «скролл не работает»: лента постоянно перестраивалась вместо обычной прокрутки.
    var pendingAnchorId by remember { mutableStateOf<String?>(null) }
    // Размер ленты в момент запроса догрузки: по нему отличаем нашу догрузку от постороннего
    // переэмита того же окна (touch updatedAt, фоновая аннотация).
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
        // Посторонний переэмит того же окна (размер не вырос, якорь на месте, старые ещё есть) — НЕ
        // наша догрузка: держим якорь, иначе он сбрасывался бы, не дождавшись префикса, и лента
        // «съедала» жест догрузки. Ждём эмит, где окно реально выросло сверху (или дошли до самых старых).
        if (state.messages.size == anchorRequestSize &&
            state.hasMoreOlder &&
            state.messages.any { it.id == anchor }
        ) {
            return@LaunchedEffect
        }
        val newIndex = state.messages.indexOfFirst { it.id == anchor }
        // Старые приклеились сверху — мгновенно (без анимации) возвращаем якорь на прежнее место.
        if (newIndex > listState.firstVisibleItemIndex) {
            listState.scrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
        }
        pendingAnchorId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Аурора — ОТДЕЛЬНЫЙ слой-источник для стекла: именно её размывают бары и навбар.
        // Раньше источником была только лента (glassSource на LazyColumn), а за баром — где ленты
        // нет — hazeEffect сэмплить было нечего и падал в плоский MaterialTheme.colorScheme.background+tint (та самая «мутная
        // плашка»). Источник обязан быть отдельным слоем: модификатор на корневом Box захватил бы
        // и сами бары (петля), поэтому аурора вынесена в собственный matchParentSize-слой позади.
        Box(
            modifier = Modifier
                .matchParentSize()
                .auroraBackground()
                .glassSource(glassState),
        )
        // Стеклянные бары «плавают» ПОВЕРХ ленты (iOS-стиль): лента заполняет весь Box и скроллится
        // ПОД шапкой и композером, поэтому за стеклом видны и размываются сами сообщения, а не только
        // аурора. Высоты баров меряем (onSizeChanged) → contentPadding ленты, чтобы крайние сообщения
        // вставали у кромки бара, а не прятались под ним.
        Box(modifier = Modifier.fillMaxSize()) {
            // fullHeight/tailSpacerHeight подняты в скоуп выше: их читает ещё и lastItemIndex(),
            // которому нужно знать, жив ли сейчас спейсер. Высота берётся из LocalWindowInfo, а НЕ из
            // BoxWithConstraints: тот — SubcomposeLayout и субкомпозировал весь экран на каждую
            // рекомпозицию тела (дорого при печати).

            // ЛЕНТА (задний слой) — заполняет весь Box и скроллится под обоими барами. Свежие сообщения
            // снизу. Arrangement.Bottom: короткий чат прижимается к низу. contentPadding сверху/снизу =
            // измеренные высоты баров, чтобы первое/последнее сообщение не уезжали под стекло.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // До первого автоскролла ленту не показываем: LazyColumn рождается на индексе 0
                    // (старые сообщения), и до снапа юзер видел вспышку начала истории. graphicsLayer
                    // читает флаг на фазе рисования — смена не рекомпозирует ленту.
                    .graphicsLayer { alpha = if (initialScrollDone) 1f else 0f }
                    // Источник для стеклянной обвязки (топбар/инпут) — сама лента НЕ размывается,
                    // только читается как backdrop чужим hazeEffect (перф: без RenderEffect в items).
                    .glassSource(glassState),
                contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                verticalArrangement = Arrangement.Bottom,
            ) {
                items(
                    state.messages,
                    key = { it.id },
                    // Подсказка Compose переиспользовать раскладку пузыря того же типа при скролле — меньше
                    // пересборок и подлагиваний на длинной ленте.
                    contentType = { it.role },
                ) { message ->
                    // Пер-итемные колбэки запоминаем по id (и по нужным флагам), чтобы они были стабильны между
                    // рекомпозициями — тогда неизменившиеся пузыри Compose пропускает.
                    val onDelete = remember(message.id) {
                        { viewModel.handleIntent(ChatIntent.DeleteMessage(message.id)) }
                    }
                    val onLessonComplete = remember(message.id, state.lessonMode, state.levelId) {
                        {
                            if (state.lessonMode) {
                                // JSON-урок: отметить пройденным и вернуться к списку.
                                viewModel.completeLesson(onNavigateBack)
                            } else {
                                // Старый roadmap-урок: финальная карточка ведёт на отдельный экран-квиз.
                                val levelId = state.levelId
                                if (levelId != null) onOpenQuiz(levelId) else onNavigateBack()
                            }
                        }
                    }
                    // CTA капстоуна (Task 23): рендерится ТОЛЬКО когда сама карточка несёт
                    // primaryAction (см. DrivenUiEngine.LessonCompleteCardView) — это и есть
                    // capstone-only гейт, здесь мы просто открываем билдер с нужным источником.
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
                    )
                }
                if (hasStreamingItem) {
                    item(key = "streaming") {
                        // Подписка на стриминговый текст ЛОКАЛЬНА для этого итема: дельты токенов рекомпозируют
                        // только пузырь, а не весь экран.
                        val streamingText by viewModel.streamingText.collectAsState()
                        StreamingBubble(
                            text = streamingText,
                            showCursor = streamingText.isNotEmpty(),
                            isWaiting = streamingText.isEmpty(),
                            fontScale = state.chatFontScale.scale,
                        )
                    }
                }
                // Резерв под ответ: пустое место в высоту ВИДИМОГО вьюпорта (экран минус оба бара),
                // чтобы вопрос прибился к верхней кромке видимой области, а ответ рос в него сверху вниз.
                // Условие НЕ на hasStreamingItem, а на саму высоту: после конца хода спейсер ещё жив,
                // пока схлопывается анимацией (см. tailSpacerHeight выше).
                if (tailSpacerHeight > 0.dp) {
                    item(key = "tail_spacer") {
                        Spacer(Modifier.height(tailSpacerHeight))
                    }
                }
            }

            // ВЕРХНИЙ оверлей: стеклянная «таблетка» шапки + стат-бар, под статус-баром.
            // Высоту меряем (onSizeChanged → topBarHeightPx) → contentPadding.top ленты. statusBarsPadding
            // живёт на самой таблетке (ребёнок Column), поэтому измеренная высота уже включает его инсет.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeightPx = it.height },
            ) {
                // Плавающая стеклянная «таблетка» шапки — как нижний навбар: скруглённая, с боковыми
                // отступами, мягкой тенью и рим-бордером (от GlassSurface). Статус-бар-инсет вынесен на
                // саму таблетку (statusBarsPadding), поэтому дети (TopAppBar/строка поиска) свой инсет НЕ
                // добавляют — иначе он задвоился бы и таблетка ушла бы под системные часы/вырез.
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
                                            text = state.title ?: "Чат",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = onNavigateBack) {
                                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Назад")
                                    }
                                },
                                actions = {
                                    // Поиск по сообщениям чата.
                                    IconButton(onClick = { searchActive = true; viewModel.setSearchActive(true) }) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = "Поиск по чату",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // Переключатель трассы инструментов показываем ТОЛЬКО когда трасса есть — иначе кнопка была бы
                                    // «мёртвой» (раньше это была шестерёнка с пустым меню).
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
                                    // Тап открывает полноэкранный AgentDetailScreen (Task 21). Только для агент-чатов.
                                    if (state.agentId != null) {
                                        IconButton(onClick = { onOpenAgentDetail(state.agentId!!) }) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Открыть настройки агента",
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

                // Контекст/токены — тонкий прогресс под шапкой. Показываем, пока есть статистика (в т.ч.
                // во время загрузки — тогда виден контекст прошлого хода).
                if (state.lastResponseStats != null) {
                    ResponseStatsBar(
                        stats = state.lastResponseStats!!,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // НИЖНИЙ оверлей: композер и все нижние бары. Высоту меряем (onSizeChanged → bottomBarHeightPx)
            // → contentPadding.bottom ленты и отступ FAB. onSizeChanged СТОИТ ПЕРЕД windowInsetsPadding,
            // чтобы измеренная высота ВКЛЮЧАЛА нижний инсет (навбар/IME): safeDrawing bottom = max(навбар,
            // клавиатура), без двойного счёта — navigationBars/imePadding поверх НЕ добавляем.
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

                // Tool-approval бар (бэкенд — Task 9, UI — Task 21): BEFORE_TOOL-хук потребовал
                // подтверждения, ход стоит на паузе в ожидании решения. В отличие от PlanCard/
                // ModeSelector ниже — сознательно НЕ гейтится на state.agentId == null: подтверждение
                // тула — ровно то, что триггерят хуки кастомных агентов (Задача 8/9).
                state.pendingToolApproval?.let { request ->
                    ToolApprovalBar(
                        request = request,
                        onApprove = { viewModel.handleIntent(ChatIntent.ApproveToolCall) },
                        onDeny = { viewModel.handleIntent(ChatIntent.DenyToolCall) },
                    )
                }

                // Живая карточка плана (Task 12) — над полем ввода. Показывает proposal-превью
                // (Одобрить/Попросить правки) пока pendingPlanApproval не null, иначе компактный
                // прогресс, пока activePlanId (ACTIVE/PAUSED) не null; сама подписывается на полный
                // PlanSnapshot по id через viewModel.observePlan — см. PlanCard.kt kdoc. Действия идут
                // напрямую типизированными ChatIntent-командами в PlanRepository/PlanCommands (Task 5),
                // никакого второго механизма approval здесь не заводится.
                // (кластер A) План живёт у агента: карточку плана показываем в чатах, привязанных к
                // мини-агенту (agentId != null) — планировщик без агента не работает, а в обычном чате
                // плановых режимов теперь и нет (см. availableModesFor ниже).
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

                // Поле ввода — внизу, как в обычном чате. Во время ответа показывает кнопку «стоп».
                // В режиме урока ввод не нужен — контроллер сам ведёт шаги (кнопка «Дальше» внизу экрана).
                AnimatedVisibility(visible = inputVisible) {
                    Column {
                        // (3-агенты, кластер A) Плановые режимы живут у агентов: PLAN/GOAL требуют
                        // резолвящегося агента (планировщик без него падает), поэтому обычный чат
                        // получает только Чат/Рефлексию, а привязанный к мини-агенту — полный набор.
                        // Скрываем селектор в гайд-уроках.
                        if (!state.isTutorial) {
                            // Селектор лежал БЕЗ подложки прямо поверх ленты: сообщения просвечивали
                            // сквозь «Чат»/«Рефлексию», и текст мешался с чипами до неразборчивости.
                            // Даём ему ту же плашку, что и у остальной нижней обвязки.
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
                        // Во время записи поле ввода подменяется панелью записи (как в Telegram/Claude): слева —
                        // отмена, по центру статус/таймер/волна/живой текст, справа — завершение. Итог падает в поле.
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
                                // inputText — из отдельного потока, читаем узким скоупом: печать
                                // рекомпозирует только композер, а не всё тело чата (лента/оверлеи).
                                val inputText by viewModel.inputText.collectAsState()
                                ChatInput(
                                    value = inputText,
                                    onValueChange = { viewModel.handleIntent(ChatIntent.UpdateInput(it)) },
                                    onSend = { viewModel.handleIntent(ChatIntent.SendMessage) },
                                    onCancel = { viewModel.handleIntent(ChatIntent.CancelRequest) },
                                    isLoading = state.isLoading,
                                    glassState = glassState,
                                    placeholder = when (state.agentMode) {
                                        AgentMode.PLAN -> "Опиши задачу — агент составит план…"
                                        AgentMode.GOAL -> "Сформулируй цель…"
                                        AgentMode.REFLECT -> "Спроси — агент даст черновик, критику и итог…"
                                        // TUTOR не выбирается пользователем (только разовый вопрос в уроке) — как обычный чат.
                                        AgentMode.CHAT, AgentMode.TUTOR -> "Сообщение..."
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
                    // Урок драйвит контроллер: пользователь листает шаги кнопкой, текстовый инпут не нужен.
                    val currentStep = state.lesson?.steps?.getOrNull(state.lessonStepIndex - 1)
                    // Ручная кнопка — для шагов без собственного «завершения»: текст, картинка и карточки-
                    // отображения (reveal/concept/example). Интерактивные (quiz/fill/sort/match/scenario) двигают
                    // урок сами по факту прохождения; исключение — вырожденный quiz без вопросов, ему нужна кнопка.
                    // "video" — тоже с кнопкой: снять ролик можно не всегда (нет OpenRouter, не та обстановка),
                    // и урок не должен на этом застревать.
                    val displayStep = currentStep?.type in
                            setOf("text", "image", "video", "animation", "reveal", "concept", "example", "input", "challenge")
                    // !isLoading: во время стрима tutor-ответа не даём листать урок, иначе следующий шаг
                    // впечатается в живой ход. Кнопка вернётся, когда ответ дойдёт. По той же причине прячем
                    // её, пока модель смотрит видео: ответ про ролик должен лечь под своим шагом.
                    val needsManualAdvance = currentStep != null && !state.lessonFinished && !state.isLoading &&
                            state.analyzingVideoPrompts.isEmpty() &&
                            (displayStep || (currentStep.type == "quiz" && currentStep.questions.isEmpty()))
                    // «Спросить» — свободный вопрос к LLM в любой момент урока (пока он не завершён).
                    // Курсор персистится отдельно (lessonStepIndex), так что вопрос не сдвигает шаги.
                    if (!state.lessonFinished) {
                        LessonAskBar(
                            enabled = !state.isLoading,
                            onAsk = { viewModel.handleIntent(ChatIntent.AskDuringLesson(it)) },
                        )
                    }
                    if (needsManualAdvance) {
                        val isLastStep = state.lessonStepIndex >= (state.lesson?.steps?.size ?: 0)
                        LessonControlBar(
                            label = if (isLastStep) "Завершить урок" else "Дальше →",
                            onClick = { viewModel.handleIntent(ChatIntent.AdvanceLesson) },
                        )
                    }
                }
            }
        }

        // Плавающая кнопка быстрого скролла вниз — когда уехали вверх по истории. Садится ровно НАД
        // композером: bottomPad = измеренная высота нижнего оверлея (уже с навбар/IME-инсетом) + 16dp
        // запаса, иначе FAB налезал бы на кнопку «Отправить». Когда во время чтения истории пришло
        // новое сообщение, кнопка подсвечивается акцентом (вместо прежнего принудительного прыжка вниз).
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomPad + 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Во время хода FAB прячем: снизу висит tail-spacer, поэтому canScrollForward всегда true
            // (иначе кнопка «вниз» маячила бы на каждом ответе и вела бы в пустой резерв).
            AnimatedVisibility(visible = showScrollDown && !hasStreamingItem) {
                SmallFloatingActionButton(
                    onClick = {
                        pinnedToBottom = true
                        hasUnseenNew = false
                        // Оффсет MAX_VALUE — до самого низа (см. комментарий у автоскролла): без него
                        // длинное последнее сообщение выравнивалось верхом и низ оставался обрезан.
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
                        contentDescription = if (hasUnseenNew) "Вниз — есть новое сообщение" else "Вниз",
                        tint = if (hasUnseenNew) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            LocalContentColor.current
                        },
                    )
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

