package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal enum class Screen(val title: String, val icon: ImageVector) {
  GENERATE("Генерация", Icons.Default.Image),
  HISTORY("История", Icons.Default.PhotoLibrary),

  /** Отладочный: в рельсе появляется, только когда журнал включён в настройках. */
  JOURNAL("Журнал запросов", Icons.Default.Terminal),
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
  val settings by container.settings.collectAsState()

  // Выключили журнал, стоя на его экране, — уходим с него сами. Иначе пользователь остался бы на
  // экране, которого в рельсе уже нет, и вернуться на него было бы неоткуда.
  if (screen == Screen.JOURNAL && !settings.requestJournal) screen = Screen.GENERATE

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(Modifier.fillMaxSize()) {
      IconRail(
        current = screen,
        keyMissing = apiKey.isBlank(),
        journalVisible = settings.requestJournal,
        onSelect = { screen = it },
      )
      VerticalDivider()
      Column(Modifier.fillMaxSize().padding(20.dp)) {
        when (screen) {
          Screen.GENERATE -> GenerateScreen(container, onOpenSettings = { screen = Screen.SETTINGS })
          Screen.HISTORY -> HistoryScreen(container, onOpenGenerate = { screen = Screen.GENERATE })
          Screen.JOURNAL -> JournalScreen(container)
          Screen.SETTINGS -> SettingsScreen(container)
        }
      }
    }
  }
}

/**
 * Узкая рельса вместо текстовых вкладок: экран двухпанельный, и 220 dp под подписи вроде
 * «Генерация / промпт → результат» отнимали место у формы.
 *
 * Только иконки, без подписей — по макету. Экранов три, они не меняются, и подпись под каждой
 * читается ровно один раз в жизни; вместо неё — подсказка при наведении, чтобы догадка не стоила
 * пользователю клика.
 *
 * «Настройки» прижаты вниз отдельно от остальных: это не третий равноправный экран, а служебный.
 */
@Composable
internal fun IconRail(
  current: Screen,
  keyMissing: Boolean,
  onSelect: (Screen) -> Unit,
  /** Журнал — отладочный экран: без включённого тумблера его в рельсе нет вовсе. */
  journalVisible: Boolean = false,
) {
  Column(
    Modifier.width(72.dp).fillMaxHeight().padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val hidden = setOfNotNull(Screen.SETTINGS, Screen.JOURNAL.takeIf { !journalVisible })
    for (item in Screen.entries - hidden) {
      RailItem(item, selected = item == current, badge = false) { onSelect(item) }
    }
    Spacer(Modifier.weight(1f))
    // Без ключа не работает ничего, поэтому точка на «Настройках» видна с любого экрана.
    RailItem(Screen.SETTINGS, selected = current == Screen.SETTINGS, badge = keyMissing) {
      onSelect(Screen.SETTINGS)
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailItem(item: Screen, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
  val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
  TooltipArea(tooltip = {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(
        item.title,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }) {
    Box(
      Modifier
        .size(44.dp)
        .clip(MaterialTheme.shapes.medium)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
        .border(
          width = 1.dp,
          color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
          shape = MaterialTheme.shapes.medium,
        )
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(item.icon, contentDescription = item.title, tint = tint)
      if (badge) {
        Box(
          Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        )
      }
    }
  }
}
