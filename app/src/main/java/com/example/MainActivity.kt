package com.aiopenpocket

import android.Manifest
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

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                            LaunchedEffect(onboardingCompleted) {
                                if (onboardingCompleted == true) {
                                    navController.navigate("roadmap") {
                                        popUpTo("onboarding") { inclusive = true }
                                        launchSingleTop = true
                                    }
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
                                        onNavigateToChats = {
                                            navController.navigate("conversations") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onNavigateToLibrary = {
                                            navController.navigate("library") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onNavigateToSettings = {
                                            navController.navigate("settings")
                                        },
                                    )
                                }
                                composable("library") {
                                    LibraryScreen(
                                        onNavigateToChats = {
                                            navController.navigate("conversations") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onNavigateToPath = {
                                            navController.navigate("roadmap") {
                                                launchSingleTop = true
                                            }
                                        },
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
                                        onNavigateToRoadmap = {
                                            navController.navigate("roadmap") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onNavigateToLibrary = {
                                            navController.navigate("library") {
                                                launchSingleTop = true
                                            }
                                        },
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
}
