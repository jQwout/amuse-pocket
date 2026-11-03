package com.aiopenpocket.ui.assistant

// @trace voice-assistant-mode-a81a99

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.aiopenpocket.AiOpenPocketApp
import com.aiopenpocket.MainActivity
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.flow.onEach

/**
 * The window the assistant button opens: translucent, so the app that was on screen stays visible under
 * [AssistantOverlay]. Always dark, whatever the app theme: white text over a dimmed screen.
 *
 * Before onboarding is done there is no key and no language yet, so it hands over to [MainActivity],
 * which shows onboarding.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appGraph = (application as AiOpenPocketApp).appGraph

        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides appGraph.metroViewModelFactory) {
                MyApplicationTheme(mode = ThemeMode.DARK) {
                    val prefs = remember { appGraph.userPreferencesRepository }
                    val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = null)
                    val language by remember { prefs.language.onEach { AppLanguage.code = it } }
                        .collectAsState(initial = null)
                    LaunchedEffect(onboardingCompleted) {
                        if (onboardingCompleted == false) openApp(null)
                    }
                    if (onboardingCompleted == true && language != null) {
                        AssistantOverlay(onDismiss = ::finish, onOpenChat = ::openApp)
                    }
                }
            }
        }
    }

    /** Continues [conversationId] in the app, or just opens the app when nothing was asked yet. */
    private fun openApp(conversationId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!conversationId.isNullOrBlank()) {
                action = MainActivity.ACTION_OPEN_CONVERSATION
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        startActivity(intent)
        finish()
    }
}
