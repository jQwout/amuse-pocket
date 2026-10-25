package com.aiopenpocket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiopenpocket.ui.components.glass.StatusBarScrim
import com.aiopenpocket.ui.chat.ChatScreen
import com.aiopenpocket.ui.conversations.ConversationsListScreen
import com.aiopenpocket.ui.agents.AgentsScreen
import com.aiopenpocket.ui.agents.builder.AgentBuilderRoute
import com.aiopenpocket.ui.agents.builder.AgentSourceContext
import com.aiopenpocket.ui.agents.detail.AgentDetailRoute
import com.aiopenpocket.ui.home.HomeScreen
import com.aiopenpocket.ui.memory.MemoryGraphScreen
import com.aiopenpocket.ui.memory.MemoryPageScreen
import com.aiopenpocket.ui.onboarding.OnboardingScreen
import com.aiopenpocket.ui.plan.PlanDetailRoute
import com.aiopenpocket.ui.plan.PlanListRoute
import com.aiopenpocket.ui.roadmap.LibraryScreen
import com.aiopenpocket.ui.roadmap.RoadmapScreen
import com.aiopenpocket.ui.scheduledworks.ScheduledWorksScreen
import com.aiopenpocket.ui.settings.SettingsScreen
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Длительность перехода между экранами. 260мс — сознательный компромисс: короче читается как
 * подмигивание и смысла в анимации нет, длиннее начинает восприниматься как тормоза приложения.
 * Easing стандартный material-ный: быстрый старт, мягкое торможение.
 */
private const val NAV_ANIM_MS = 260

/** Спеки разнесены по типам: сдвиг анимирует IntOffset, прозрачность — Float, общий на оба не годится. */
private val NAV_SLIDE = tween<androidx.compose.ui.unit.IntOffset>(NAV_ANIM_MS, easing = FastOutSlowInEasing)
private val NAV_FADE = tween<Float>(NAV_ANIM_MS, easing = FastOutSlowInEasing)

class MainActivity : ComponentActivity() {

    // Увеличивается каждый раз, когда приложение открыто как голосовой помощник —
    // сигнал для UI открыть новый чат.
    private val openNewChatRequest = MutableStateFlow(0)

    // Id диалога из тапа по уведомлению «агент ответил» — сигнал UI открыть этот чат.
    private val openConversationRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Интент запуска разбираем только при «чистом» старте. После поворота, смены системной темы
        // или восстановления процесса getIntent() — всё тот же исходный интент, а стек навигации уже
        // восстановлен: повторный разбор добавлял поверх лишние home и пустой chat/new (после вызова
        // ассистента) или второй раз открывал чат из уведомления. Запуск из «Недавних» повторяет
        // старый интент так же — его тоже пропускаем.
        // Разрешение на уведомления здесь больше не спрашиваем: системный диалог до онбординга, без
        // объяснения зачем, — лёгкий повод нажать «Запретить». Его просят по делу: карточка
        // notification_permission, которую агент показывает, когда heartbeat упирается в отказ.
        if (savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) {
            handleLaunchIntent(intent)
        }

        enableEdgeToEdge()

        val appGraph = (application as AiOpenPocketApp).appGraph

        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
            ) {
                val themeMode by remember { appGraph.themeRepository.mode }.collectAsState()
                MyApplicationTheme(mode = themeMode) {
                    val userPreferencesRepository = remember { appGraph.userPreferencesRepository }
                    val onboardingCompleted by userPreferencesRepository.onboardingCompleted
                        .collectAsState(initial = null)

                    when (onboardingCompleted) {
                        null -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        else -> {
                            val navController = rememberNavController()
                            val newChatToken by openNewChatRequest.collectAsState()
                            // Стартовый экран выбираем сразу по факту прохождения онбординга — иначе
                            // NavHost сперва рисует "onboarding", и только потом LaunchedEffect уводит
                            // на "home", из-за чего у уже прошедшего пользователя онбординг мелькает.
                            // К этому моменту onboardingCompleted уже не null (мы в ветке else).
                            val startDestination = if (onboardingCompleted == true) "home" else "onboarding"
                            // Подстраховка на случай, если онбординг завершится уже после старта
                            // (false → true): уводим на home, сняв онбординг со стека.
                            LaunchedEffect(onboardingCompleted) {
                                if (onboardingCompleted == true &&
                                    navController.currentDestination?.route == "onboarding"
                                ) {
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            }
                            // Открываем новый чат при вызове голосового помощника.
                            // Онбординг должен быть завершён — иначе чат недоступен.
                            LaunchedEffect(newChatToken, onboardingCompleted) {
                                if (newChatToken > 0 && onboardingCompleted == true) {
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    navController.navigate("chat/new") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            // Тап по уведомлению «агент ответил»: открываем тот самый диалог.
                            val openConversationId by openConversationRequest.collectAsState()
                            LaunchedEffect(openConversationId, onboardingCompleted) {
                                val convId = openConversationId
                                if (convId != null && onboardingCompleted == true) {
                                    // Id уже проверен (UUID) в handleLaunchIntent; encode — на случай,
                                    // если формат id когда-нибудь расширят: «/» в маршруте роняла навигацию.
                                    navController.navigate("chat/${Uri.encode(convId)}") { launchSingleTop = true }
                                    openConversationRequest.value = null
                                }
                            }
                            // Переключение вкладок нижнего бара: держим стек плоским —
                            // «home» как база, текущая вкладка сверху, с сохранением состояния.
                            val navigateToTab: (String) -> Unit = { route ->
                                navController.navigate(route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            // Фейд под часами — ОДИН на всё приложение, поверх NavHost: экраны про
                            // него не знают и не могут забыть его добавить. Он ничего не
                            // перехватывает (просто фон), поэтому лежать поверх безопасно.
                            Box(modifier = Modifier.fillMaxSize()) {
                            // Переходы между экранами — один раз на весь NavHost, а не по маршрутам:
                            // иначе 18 destination'ов пришлось бы держать в синхроне вручную, и
                            // рано или поздно один поехал бы. Жест простой и не «мультяшный»:
                            // новый экран въезжает с небольшим сдвигом и проявляется, старый уходит
                            // симметрично; назад — зеркально, чтобы направление читалось.
                            // Сдвиг маленький (1/8 ширины): полноэкранная проездка на каждом
                            // переходе быстро надоедает и субъективно замедляет приложение.
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                enterTransition = {
                                    slideInHorizontally(NAV_SLIDE) { it / 8 } + fadeIn(NAV_FADE)
                                },
                                exitTransition = {
                                    slideOutHorizontally(NAV_SLIDE) { -it / 8 } + fadeOut(NAV_FADE)
                                },
                                popEnterTransition = {
                                    slideInHorizontally(NAV_SLIDE) { -it / 8 } + fadeIn(NAV_FADE)
                                },
                                popExitTransition = {
                                    slideOutHorizontally(NAV_SLIDE) { it / 8 } + fadeOut(NAV_FADE)
                                },
                            ) {
                                composable("onboarding") {
                                    // Навигация после завершения управляется наблюдателем
                                    // onboardingCompleted (LaunchedEffect выше) — единый источник.
                                    OnboardingScreen()
                                }
                                composable("onboarding_edit") {
                                    // Урок 1 = онбординг: повторный вход с карты для правки настроек.
                                    OnboardingScreen(onClose = { navController.popBackStack() })
                                }
                                composable("home") {
                                    HomeScreen(
                                        onOpenChat = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                        onNavigateToConversations = {
                                            navController.navigate("conversations")
                                        },
                                        onNavigateToLibrary = {
                                            navController.navigate("library")
                                        },
                                        onNavigateToRoadmap = { navigateToTab("roadmap") },
                                        onNavigateToSettings = { navigateToTab("settings") },
                                        onNavigateToAgents = { navController.navigate("agents") },
                                    )
                                }
                                composable("agents") {
                                    AgentsScreen(
                                        onOpenAgent = { id -> navController.navigate("agent/$id") },
                                        onCreateAgent = { navController.navigate("agent_builder") },
                                        // Черновик открывается сразу в конструкторе: чата у него ещё нет.
                                        onEditDraft = { id -> navController.navigate("agent_builder/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable("agent/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    AgentDetailRoute(
                                        agentId = agentId,
                                        onOpenChat = { id -> navController.navigate("chat/$id") },
                                        onNavigateToBuilder = { id -> navController.navigate("agent_builder/$id") },
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenPlan = { planId -> navController.navigate("plan/$planId") },
                                        onOpenPlanList = { id -> navController.navigate("plans/$id") },
                                    )
                                }
                                composable("plans/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                                    PlanListRoute(
                                        agentId = agentId,
                                        onOpenPlan = { planId -> navController.navigate("plan/$planId") },
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable("plan/{planId}") { backStackEntry ->
                                    val planId = backStackEntry.arguments?.getString("planId") ?: ""
                                    PlanDetailRoute(
                                        planId = planId,
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable(
                                    route = "agent_builder?source={source}",
                                    arguments = listOf(
                                        // Капстоун Академии (Task 23): ChatScreen's onOpenAgentBuilder навигирует сюда
                                        // с source=ACADEMY_CAPSTONE. Все прочие входы (FAB "Создать агента" и т.п.)
                                        // навигируют на голое "agent_builder" — источник берёт defaultValue = MANUAL.
                                        navArgument("source") {
                                            type = NavType.StringType
                                            defaultValue = AgentSourceContext.MANUAL.name
                                        },
                                    ),
                                ) { backStackEntry ->
                                    val sourceContext = backStackEntry.arguments?.getString("source")
                                        ?.let { runCatching { AgentSourceContext.valueOf(it) }.getOrNull() }
                                        ?: AgentSourceContext.MANUAL
                                    AgentBuilderRoute(
                                        agentId = null,
                                        sourceContext = sourceContext,
                                        onExit = { navController.popBackStack() },
                                        // Опубликованного агента — сразу в его чат; конструктор закрываем,
                                        // чтобы «Назад» из чата не возвращал в уже законченный мастер.
                                        onOpenChat = { id ->
                                            navController.popBackStack()
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable("agent_builder/{agentId}") { backStackEntry ->
                                    val agentId = backStackEntry.arguments?.getString("agentId")
                                    AgentBuilderRoute(
                                        agentId = agentId,
                                        onExit = { navController.popBackStack() },
                                        onOpenChat = { id ->
                                            navController.popBackStack()
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable("roadmap") {
                                    RoadmapScreen(
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                        onReopenOnboarding = {
                                            navController.navigate("onboarding_edit")
                                        },
                                        onNavigateToHome = { navigateToTab("home") },
                                        onNavigateToSettings = { navigateToTab("settings") },
                                    )
                                }
                                composable("library") {
                                    LibraryScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                    )
                                }
                                composable("conversations") {
                                    ConversationsListScreen(
                                        onConversationClick = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                        onNavigateBack = { navController.popBackStack() },
                                        onNavigateToGraph = {
                                            navController.navigate("memory_graph") {
                                                launchSingleTop = true
                                            }
                                        },
                                    )
                                }
                                composable("memory_graph") {
                                    MemoryGraphScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenChat = { id ->
                                            navController.navigate("memory_page/$id")
                                        },
                                    )
                                }
                                composable("memory_page/{conversationId}") { backStackEntry ->
                                    val conversationId =
                                        backStackEntry.arguments?.getString("conversationId") ?: ""
                                    MemoryPageScreen(
                                        conversationId = conversationId,
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenChat = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                    )
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onNavigateToScheduledWorks = { navController.navigate("scheduled_works") },
                                        onNavigateToHome = { navigateToTab("home") },
                                        onNavigateToRoadmap = { navigateToTab("roadmap") },
                                    )
                                }
                                composable("scheduled_works") {
                                    ScheduledWorksScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                    )
                                }
                                composable("chat/{conversationId}") { backStackEntry ->
                                    val conversationId =
                                        backStackEntry.arguments?.getString("conversationId") ?: "new"
                                    ChatScreen(
                                        conversationId = if (conversationId == "new") "" else conversationId,
                                        onNavigateBack = { navController.popBackStack() },
                                        onOpenAgentDetail = { agentId ->
                                            navController.navigate("agent/$agentId")
                                        },
                                        onOpenAgentBuilder = { source ->
                                            navController.navigate("agent_builder?source=${source.name}")
                                        },
                                        onOpenPlanDetail = { planId ->
                                            navController.navigate("plan/$planId")
                                        },
                                    )
                                }
                            }

                            StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * Превращает интент запуска в разовый сигнал UI и «съедает» его: действие и id диалога
     * стираются, чтобы тот же объект интента уже ничего не значил, если его прочтут ещё раз.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_OPEN_NEW_CHAT, Intent.ACTION_ASSIST -> openNewChatRequest.value += 1
            ACTION_OPEN_CONVERSATION ->
                validConversationIdOrNull(intent.getStringExtra(EXTRA_CONVERSATION_ID))
                    ?.let { openConversationRequest.value = it }
            else -> return
        }
        intent.action = null
        intent.removeExtra(EXTRA_CONVERSATION_ID)
    }

    companion object {
        /** Открыть новый чат (приложение вызвано как голосовой помощник). */
        const val ACTION_OPEN_NEW_CHAT = "com.aiopenpocket.action.OPEN_NEW_CHAT"

        /** Тап по уведомлению «агент ответил» — открыть конкретный диалог. */
        const val ACTION_OPEN_CONVERSATION = "com.aiopenpocket.action.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        private val CONVERSATION_ID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * Активити экспортирована (лаунчер/ассистент), так что интент с ACTION_OPEN_CONVERSATION может
         * прислать любое приложение. Id диалогов у нас — только UUID (ConversationRepository), всё
         * прочее отбрасываем: id с «/» раньше бросал исключение прямо в LaunchedEffect навигации.
         */
        internal fun validConversationIdOrNull(raw: String?): String? =
            raw?.trim()?.takeIf { CONVERSATION_ID_REGEX.matches(it) }
    }
}
