package com.aiopenpocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiopenpocket.ui.chat.ChatScreen
import com.aiopenpocket.ui.conversations.ConversationsListScreen
import com.aiopenpocket.ui.agents.AgentsScreen
import com.aiopenpocket.ui.home.HomeScreen
import com.aiopenpocket.ui.memory.MemoryGraphScreen
import com.aiopenpocket.ui.memory.MemoryPageScreen
import com.aiopenpocket.ui.onboarding.OnboardingScreen
import com.aiopenpocket.ui.roadmap.LibraryScreen
import com.aiopenpocket.ui.roadmap.QuizScreen
import com.aiopenpocket.ui.roadmap.RoadmapScreen
import com.aiopenpocket.ui.scheduledworks.ScheduledWorksScreen
import com.aiopenpocket.ui.settings.SettingsScreen
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Увеличивается каждый раз, когда приложение открыто как голосовой помощник —
    // сигнал для UI открыть новый чат.
    private val openNewChatRequest = MutableStateFlow(0)

    // Id диалога из тапа по уведомлению «агент ответил» — сигнал UI открыть этот чат.
    private val openConversationRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOpenNewChatIntent(intent)) {
            openNewChatRequest.value += 1
        }
        handleOpenConversationIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        enableEdgeToEdge()

        val appGraph = (application as AiOpenPocketApp).appGraph

        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
            ) {
                MyApplicationTheme {
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
                                    navController.navigate("chat/$convId") { launchSingleTop = true }
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
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
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
                                        onOpenChat = { id -> navController.navigate("chat/$id") },
                                        onNavigateBack = { navController.popBackStack() },
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
                                        onOpenQuiz = { levelId ->
                                            navController.navigate("quiz/$levelId")
                                        },
                                    )
                                }
                                composable("quiz/{levelId}") { backStackEntry ->
                                    val levelId = backStackEntry.arguments?.getString("levelId") ?: ""
                                    QuizScreen(
                                        levelId = levelId,
                                        onFinished = {
                                            // Назад к карте: снимаем чат и экран-квиз со стека.
                                            // Если карты нет в стеке (туториал открыли из списка чатов) — идём на неё.
                                            val popped = navController.popBackStack("roadmap", inclusive = false)
                                            if (!popped) {
                                                navController.navigate("roadmap") { launchSingleTop = true }
                                            }
                                        },
                                    )
                                }
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
        if (isOpenNewChatIntent(intent)) {
            openNewChatRequest.value += 1
        }
        handleOpenConversationIntent(intent)
    }

    private fun isOpenNewChatIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_OPEN_NEW_CHAT || intent?.action == Intent.ACTION_ASSIST

    private fun handleOpenConversationIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CONVERSATION) {
            intent.getStringExtra(EXTRA_CONVERSATION_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { openConversationRequest.value = it }
        }
    }

    companion object {
        /** Открыть новый чат (приложение вызвано как голосовой помощник). */
        const val ACTION_OPEN_NEW_CHAT = "com.aiopenpocket.action.OPEN_NEW_CHAT"

        /** Тап по уведомлению «агент ответил» — открыть конкретный диалог. */
        const val ACTION_OPEN_CONVERSATION = "com.aiopenpocket.action.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
