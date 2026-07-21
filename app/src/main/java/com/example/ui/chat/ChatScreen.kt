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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import com.aiopenpocket.ui.chat.components.ChatSearchBar
import com.aiopenpocket.ui.chat.components.LessonAskBar
import com.aiopenpocket.ui.chat.components.LessonControlBar
import com.aiopenpocket.ui.chat.components.MicButton
import com.aiopenpocket.ui.chat.components.ModeSelector
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

    // Перф-диагностика лага: настоящий backdrop-blur (Haze) на шапке и композере пере-считывается
    // каждый кадр, пока под баром что-то движется (скролл ленты / стрим ответа) — единственная
    // непрерывно GPU-тяжёлая работа на экране. Гасим блюр на это время (дешёвый tint), возвращаем в
    // покое. Если на устройстве скролл/печать заметно глаже — виновник блюр (иначе см. markdown-парс).
    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val glassBlurEnabled = !isScrolling && !hasStreamingItem

    val inputVisible = !state.lessonMode && !searchActive
    val showScrollDown by remember {
        derivedStateOf { listState.canScrollForward }
    }

    // Индекс последнего элемента ленты: сообщения + (опционально) стриминговый пузырь.
    fun lastItemIndex() = state.messages.size - 1 + if (hasStreamingItem) 1 else 0

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
                if (qIndex >= 0) listState.scrollToItem(qIndex, 0)
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
                if (target >= 0) listState.scrollToItem(target, Int.MAX_VALUE)
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
            // Высота окна для tail-спейсера. Берём из LocalWindowInfo, а НЕ из BoxWithConstraints: тот —
            // SubcomposeLayout и субкомпозировал весь экран на каждую рекомпозицию тела (дорого при печати).
            // Видимый вьюпорт между барами = fullHeight - topPad - bottomPad.
            val fullHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

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
                    // Резерв под ответ: пустое место в высоту ВИДИМОГО вьюпорта (экран минус оба бара),
                    // чтобы вопрос прибился к верхней кромке видимой области, а ответ рос в него сверху вниз.
                    item(key = "tail_spacer") {
                        Spacer(Modifier.height((fullHeight - topPad - bottomPad).coerceAtLeast(0.dp)))
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
                    blurEnabled = glassBlurEnabled,
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
                val livePlanId = state.pendingPlanApproval?.planId ?: state.activePlanId
                if (livePlanId != null && !state.lessonMode && !state.isTutorial && state.agentId == null) {
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
                        onOpenDetail = onOpenPlanDetail,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                // Поле ввода — внизу, как в обычном чате. Во время ответа показывает кнопку «стоп».
                // В режиме урока ввод не нужен — контроллер сам ведёт шаги (кнопка «Дальше» внизу экрана).
                AnimatedVisibility(visible = inputVisible) {
                    Column {
                        // (3-агенты) Режимы план/гоал — только в обычных чатах: прячем их в гайд-уроках (l2–l4) и в
                        // чатах, привязанных к мини-агенту (там доступен только обычный чат-режим).
                        if (!state.isTutorial && state.agentId == null) {
                            ModeSelector(
                                selected = state.agentMode,
                                enabled = !state.isLoading,
                                onSelect = { viewModel.handleIntent(ChatIntent.SetMode(it)) },
                            )
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
                                    blurEnabled = glassBlurEnabled,
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
                    val displayStep = currentStep?.type in
                            setOf("text", "image", "reveal", "concept", "example", "input", "challenge")
                    // !isLoading: во время стрима tutor-ответа не даём листать урок, иначе следующий шаг
                    // впечатается в живой ход. Кнопка вернётся, когда ответ дойдёт.
                    val needsManualAdvance = currentStep != null && !state.lessonFinished && !state.isLoading &&
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

/**
 * Строка поиска в шапке: поле ввода + счётчик совпадений и стрелки навигации (пред/след), крестик —
 * закрыть поиск. Скролл к совпадениям и подсветка пузыря — на стороне [ChatScreen].
 */

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

// internal: см. MessageBubble — нужен превью-каркасу (в т.ч. ChatInputStatesPreview).
@Composable
internal fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    glassState: GlassState,
    blurEnabled: Boolean = true,
    placeholder: String = "Сообщение...",
    onExpand: () -> Unit,
    voiceState: VoiceInputState,
    onVoiceToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
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

    // Единая скруглённая рамка: сверху — текст, снизу — панель управления (как в макете). Blur/tint/
    // блик-бордер — от GlassSurface; фиолетовый акцент-бордер оставлен поверх как второй, внутренний.
    // Плавающее оформление как у навбара/шапки: боковые отступы + мягкая тень (рим — от GlassSurface).
    val inputShape = RoundedCornerShape(24.dp)
    GlassSurface(
        state = glassState,
        shape = inputShape,
        blurEnabled = blurEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(16.dp, inputShape, clip = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = inputShape,
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
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Stop, contentDescription = "Остановить")
                        }
                    }
                } else {
                    // Кнопка-кружок: залита акцентом, когда есть что отправить; иначе приглушена (как в макете).
                    val canSend = value.isNotBlank()
                    IconButton(
                        // Отправка сворачивает клавиатуру: снимаем фокус с поля -> IME скрывается.
                        onClick = { focusManager.clearFocus(); onSend() },
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
    val focusManager = LocalFocusManager.current
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
                Button(onClick = { focusManager.clearFocus(); onSend() }, enabled = value.isNotBlank()) {
                    Text("Отправить")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
