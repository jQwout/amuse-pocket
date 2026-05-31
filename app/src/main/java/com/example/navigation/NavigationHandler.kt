package com.example.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.example.ui.main.MainViewModel

@Composable
fun AppNavigationHandler(
    navController: NavController,
    mainViewModel: MainViewModel
) {
    LaunchedEffect(Unit) {
        mainViewModel.navigationEvents.collect { event ->
            when (event) {
                is NavigationEvent.NavigateToWebView -> {
                    Log.d("NavigationHandler", "Navigating to WebView screen")
                    navController.navigate("webview") {
                        // Pop everything from backstack to prevent going back to Chat on back button click
                        popUpTo(0) { inclusive = true }
                    }
                }
                is NavigationEvent.NavigateToChat -> {
                    Log.d("NavigationHandler", "Navigating to Chat screen, popping WebView from history")
                    navController.navigate("chat") {
                        // Remove WebView completely from stack, so once cookie is retrieved, they can never return to login
                        popUpTo("webview") { inclusive = true }
                    }
                }
            }
        }
    }
}
