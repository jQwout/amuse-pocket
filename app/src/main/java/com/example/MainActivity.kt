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

                val chatViewModel: ChatViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return ChatViewModel(
                                Metro.chatRepository,
                                Metro.qwenClient,
                                Metro.navigationBridge
                            ) as T
                        }
                    }
                )

                NavHost(
                    navController = navController,
                    startDestination = "chat",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("chat") {
                        ChatScreen(viewModel = chatViewModel)
                    }
                }
            }
        }
    }
}
