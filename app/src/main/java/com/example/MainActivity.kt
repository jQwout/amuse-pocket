package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.di.Metro
import com.example.navigation.AppNavigationHandler
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.main.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.webview.WebViewScreen
import com.example.ui.webview.WebViewViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Metro Dependency Injection Graph with application Context
        Metro.initialize(this)

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()

                // Encapsulated navigation event handler delegated to external file
                AppNavigationHandler(navController = navController, mainViewModel = mainViewModel)

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
                                Metro.nvidiaClient,
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
}
