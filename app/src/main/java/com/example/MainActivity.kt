package com.example

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.di.Metro
import com.example.navigation.NavigationEvent
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.main.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.webview.WebViewScreen
import com.example.ui.webview.WebViewViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashHandler()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Metro Dependency Injection Graph with application Context
        Metro.initialize(this)

        setContent {
            MyApplicationTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                val crashPrefs = remember { context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE) }
                var lastCrash by remember { mutableStateOf(crashPrefs.getString("last_crash", null)) }

                if (lastCrash != null) {
                    AlertDialog(
                        onDismissRequest = {
                            crashPrefs.edit().remove("last_crash").apply()
                            lastCrash = null
                        },
                        title = { Text("Резервная диагностика ошибок") },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text("Приложение восстановилось после непредвиденного сбоя. Вот детали ошибки:")
                                Spacer(modifier = Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(
                                        text = lastCrash ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    crashPrefs.edit().remove("last_crash").apply()
                                    lastCrash = null
                                }
                            ) {
                                Text("Продолжить работу")
                            }
                        }
                    )
                }

                val navController = rememberNavController()

                // Collect application navigation events emitted from our MainViewModel's singleton proxy flow
                LaunchedEffect(Unit) {
                    mainViewModel.navigationEvents.collect { event ->
                        when (event) {
                            is NavigationEvent.NavigateToWebView -> {
                                Log.d("MainActivity", "Navigating to WebView screen")
                                navController.navigate("webview") {
                                    // Pop everything from backstack to prevent going back to Chat on back button click
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                            is NavigationEvent.NavigateToChat -> {
                                Log.d("MainActivity", "Navigating to Chat screen, popping WebView from history")
                                navController.navigate("chat") {
                                    // Remove WebView completely from stack, so once cookie is retrieved, they can never return to login
                                    popUpTo("webview") { inclusive = true }
                                }
                            }
                        }
                    }
                }

                // Dependent ViewModels can be retrieved inside standard composable factory scopes with constructor injection
                val webViewViewModel: WebViewViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return WebViewViewModel(
                                Metro.chatRepository,
                                Metro.navigationBridge
                            ) as T
                        }
                    }
                )
                val chatViewModel: ChatViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return ChatViewModel(
                                Metro.chatRepository,
                                Metro.geminiClient,
                                Metro.navigationBridge
                            ) as T
                        }
                    }
                )

                NavHost(
                    navController = navController,
                    startDestination = "webview",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("webview") {
                        WebViewScreen(viewModel = webViewViewModel)
                    }
                    composable("chat") {
                        ChatScreen(viewModel = chatViewModel)
                    }
                }
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
                val stackTrace = Log.getStackTraceString(throwable)
                prefs.edit().putString("last_crash", stackTrace).commit()
            } catch (e: Exception) {
                // Keep it safe
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
