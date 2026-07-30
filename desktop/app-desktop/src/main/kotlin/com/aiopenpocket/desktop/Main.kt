package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private enum class Screen(val title: String, val subtitle: String) {
  GENERATE("Генерация", "промпт → результат"),
  HISTORY("История", "что уже сделано"),
  SETTINGS("Настройки", "ключ и пути"),
}

fun main() {
  // Scope живёт столько же, сколько процесс: задания не должны умирать вместе с рекомпозицией
  // экрана, с которого их запустили.
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val container = AppContainer(appScope)

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "AI Open Pocket — генерация медиа",
      state = rememberWindowState(size = DpSize(1280.dp, 880.dp)),
    ) {
      AppTheme { AppRoot(container) }
    }
  }
}

@Composable
private fun AppRoot(container: AppContainer) {
  var screen by remember { mutableStateOf(Screen.GENERATE) }
  val apiKey by container.apiKey.collectAsState()

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(Modifier.fillMaxSize()) {
      Sidebar(
        current = screen,
        keyMissing = apiKey.isBlank(),
        onSelect = { screen = it },
      )
      VerticalDivider()
      Column(Modifier.fillMaxSize().padding(24.dp)) {
        when (screen) {
          Screen.GENERATE -> GenerateScreen(container, onOpenSettings = { screen = Screen.SETTINGS })
          Screen.HISTORY -> HistoryScreen(container)
          Screen.SETTINGS -> SettingsScreen(container)
        }
      }
    }
  }
}

@Composable
private fun Sidebar(current: Screen, keyMissing: Boolean, onSelect: (Screen) -> Unit) {
  Column(
    Modifier.width(220.dp).fillMaxHeight().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      "AI Open Pocket",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      "клиент OpenRouter",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    for (item in Screen.entries) {
      val selected = item == current
      Column(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(
            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
          )
          .clickable { onSelect(item) }
          .padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        Text(
          item.title,
          style = MaterialTheme.typography.bodyLarge,
          color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          item.subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }

    if (keyMissing) {
      Spacer(Modifier.height(16.dp))
      // Без ключа не работает вообще ничего, поэтому предупреждение видно с любого экрана.
      Text(
        "Ключ OpenRouter не задан — открой «Настройки»",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}
