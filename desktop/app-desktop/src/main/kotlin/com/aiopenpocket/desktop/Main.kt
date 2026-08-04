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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.cancel

private enum class Screen(val title: String, val icon: ImageVector) {
  GENERATE("Генерация", Icons.Default.Add),
  HISTORY("История", Icons.Default.List),
  SETTINGS("Настройки", Icons.Default.Settings),
}

fun main() {
  // Scope живёт столько же, сколько процесс: задания не должны умирать вместе с рекомпозицией
  // экрана, с которого их запустили.
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val container = AppContainer(appScope)

  application {
    Window(
      onCloseRequest = {
        // Гасим фоновые задания и сетевой движок явно: иначе потоки CIO переживают окно.
        container.close()
        appScope.cancel()
        exitApplication()
      },
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
      IconRail(
        current = screen,
        keyMissing = apiKey.isBlank(),
        onSelect = { screen = it },
      )
      VerticalDivider()
      Column(Modifier.fillMaxSize().padding(20.dp)) {
        when (screen) {
          Screen.GENERATE -> GenerateScreen(container, onOpenSettings = { screen = Screen.SETTINGS })
          Screen.HISTORY -> HistoryScreen(container)
          Screen.SETTINGS -> SettingsScreen(container)
        }
      }
    }
  }
}

/**
 * Узкая рельса вместо текстовых вкладок: экран генерации стал двухпанельным, и 220 dp под подписи
 * «Генерация / промпт → результат» отнимали место у формы. Подпись под иконкой всё же осталась —
 * три одинаково серых пиктограммы без слов угадываются хуже, чем кажется автору.
 *
 * «Настройки» прижаты вниз отдельно от остальных: это не третий равноправный экран, а служебный.
 */
@Composable
private fun IconRail(current: Screen, keyMissing: Boolean, onSelect: (Screen) -> Unit) {
  Column(
    Modifier.width(76.dp).fillMaxHeight().padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (item in Screen.entries - Screen.SETTINGS) {
      RailItem(item, selected = item == current, badge = false) { onSelect(item) }
    }
    Spacer(Modifier.weight(1f))
    // Без ключа не работает ничего, поэтому точка на «Настройках» видна с любого экрана.
    RailItem(Screen.SETTINGS, selected = current == Screen.SETTINGS, badge = keyMissing) {
      onSelect(Screen.SETTINGS)
    }
  }
}

@Composable
private fun RailItem(item: Screen, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
  val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    Modifier
      .width(60.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box {
      Icon(item.icon, contentDescription = item.title, tint = tint)
      if (badge) {
        Box(
          Modifier
            .align(Alignment.TopEnd)
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        )
      }
    }
    Text(
      item.title,
      style = MaterialTheme.typography.labelSmall,
      color = tint,
    )
  }
}
