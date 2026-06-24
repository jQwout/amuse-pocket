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
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiopenpocket.ui.chat.ChatScreen
import com.aiopenpocket.ui.conversations.ConversationsListScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOpenNewChatIntent(intent)) {
            openNewChatRequest.value += 1
        }

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
                            fun openRoadmapAfterOnboarding() {
                                navController.navigate("roadmap") {
                                    popUpTo("onboarding") { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                            LaunchedEffect(onboardingCompleted) {
                                if (onboardingCompleted == true) openRoadmapAfterOnboarding()
                            }
                            // Открываем новый чат при вызове голосового помощника.
                            // Онбординг должен быть завершён — иначе чат недоступен.
                            LaunchedEffect(newChatToken, onboardingCompleted) {
                                if (newChatToken > 0 && onboardingCompleted == true) {
                                    openRoadmapAfterOnboarding()
                                    navController.singleTop("chat/new")
                                }
                            }
                            NavHost(
                                navController = navController,
                                startDestination = "onboarding",
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
                                composable("roadmap") {
                                    RoadmapScreen(
                                        onOpenLessonChat = { chatId ->
                                            navController.navigate("chat/$chatId")
                                        },
                                        onReopenOnboarding = {
                                            navController.navigate("onboarding_edit")
                                        },
                                        onNavigateToChats = { navController.singleTop("conversations") },
                                        onNavigateToLibrary = { navController.singleTop("library") },
                                        onNavigateToSettings = {
                                            navController.navigate("settings")
                                        },
                                    )
                                }
                                composable("library") {
                                    LibraryScreen(
                                        onNavigateToChats = { navController.singleTop("conversations") },
                                        onNavigateToPath = { navController.singleTop("roadmap") },
                                        onNavigateToSettings = {
                                            navController.navigate("settings")
                                        },
                                    )
                                }
                                composable("conversations") {
                                    ConversationsListScreen(
                                        onConversationClick = { id ->
                                            navController.navigate("chat/$id")
                                        },
                                        onNavigateToSettings = {
                                            navController.navigate("settings")
                                        },
                                        onNavigateToRoadmap = { navController.singleTop("roadmap") },
                                        onNavigateToLibrary = { navController.singleTop("library") },
                                        onNavigateToGraph = { navController.singleTop("memory_graph") },
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
                                            if (!navController.popBackStack("roadmap", inclusive = false)) {
                                                navController.singleTop("roadmap")
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
    }

    private fun isOpenNewChatIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_OPEN_NEW_CHAT || intent?.action == Intent.ACTION_ASSIST

    companion object {
        /** Открыть новый чат (приложение вызвано как голосовой помощник). */
        const val ACTION_OPEN_NEW_CHAT = "com.aiopenpocket.action.OPEN_NEW_CHAT"
    }
}

private fun NavController.singleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}
