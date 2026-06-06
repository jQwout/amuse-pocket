package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.example.ui.chat.ChatScreen
import com.example.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.compose.LocalMetroViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appGraph = (application as AiOpenPocketApp).appGraph

        setContent {
            CompositionLocalProvider(
                LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
            ) {
                MyApplicationTheme {
                    ChatScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
