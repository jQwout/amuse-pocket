package lessonviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import lessonviewer.io.LessonLoader
import lessonviewer.io.LessonsDir
import lessonviewer.model.orderLessons
import lessonviewer.ui.ErrorPlate
import lessonviewer.ui.LessonListPane
import lessonviewer.ui.LessonPane

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Уроки",
    state = rememberWindowState(size = DpSize(1280.dp, 900.dp)),
  ) {
    MaterialTheme { ViewerRoot() }
  }
}

@Composable
private fun ViewerRoot() {
  var reloadTick by remember { mutableStateOf(0) }
  var selectedName by remember { mutableStateOf<String?>(null) }

  // reloadTick in the key is how we reload: recompute the list and parse the files again.
  val dir = remember(reloadTick) { LessonsDir.resolve() }
  // The order is pedagogical, as in the app (ROADMAP_LEVELS), not by file name.
  val lessons = remember(reloadTick, dir) {
    orderLessons(dir?.let { LessonLoader.loadAll(it) } ?: emptyList())
  }
  // The selection is kept by file name, not by object: after a reload the objects are new.
  val selected = lessons.firstOrNull { it.loaded.file.name == selectedName }?.loaded
  // Image paths in lessons are counted from app/src/main/assets, that is, one level above the
  // directory with the lessons themselves.
  val assetsRoot = remember(dir) { dir?.parentFile }

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(onClick = { reloadTick++ }) { Text("Перечитать") }
      Text(dir?.path ?: "каталог не найден", style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider()

    if (dir == null) {
      Column(Modifier.fillMaxSize().padding(16.dp)) {
        ErrorPlate(
          "Каталог уроков не найден.\nИскал: ${LessonsDir.expectedPath()}\n" +
            "Запусти из tools/lesson-viewer или задай -D${LessonsDir.PROPERTY}=<путь>",
        )
      }
    } else {
      Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(300.dp).fillMaxHeight()) {
          LessonListPane(lessons, selected) { selectedName = it.file.name }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { LessonPane(selected, assetsRoot) }
      }
    }
  }
}
