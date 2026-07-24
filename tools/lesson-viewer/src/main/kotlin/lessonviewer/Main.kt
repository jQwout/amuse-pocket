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

  // reloadTick в ключе — этим и перечитываем: пересчитать список и заново разобрать файлы.
  val dir = remember(reloadTick) { LessonsDir.resolve() }
  val lessons = remember(reloadTick, dir) { dir?.let { LessonLoader.loadAll(it) } ?: emptyList() }
  // Выбор храним по имени файла, а не по объекту: после перечитывания объекты новые.
  val selected = lessons.firstOrNull { it.file.name == selectedName }
  // Пути картинок в уроках отсчитываются от app/src/main/assets, то есть на уровень выше
  // каталога с самими уроками.
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
