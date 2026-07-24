package lessonviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lessonviewer.io.LoadedLesson
import lessonviewer.model.OrderedLesson

@Composable
fun LessonListPane(
  items: List<OrderedLesson>,
  selected: LoadedLesson?,
  onSelect: (LoadedLesson) -> Unit,
) {
  // Граница между путём и уроками вне пути — первый элемент без номера.
  val firstOffPathIndex = items.indexOfFirst { it.roadmapNumber == null }

  LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
    items(items) { item ->
      if (firstOffPathIndex >= 0 && item === items[firstOffPathIndex]) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
          HorizontalDivider()
          Text(
            "вне пути — в приложении недостижимы",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp, start = 8.dp),
          )
        }
      }

      val loaded = item.loaded
      val isSelected = loaded.file == selected?.file
      Row(
        Modifier
          .fillMaxWidth()
          .background(
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
          )
          .clickable { onSelect(loaded) }
          .padding(8.dp),
      ) {
        Text(
          item.roadmapNumber?.toString()?.padStart(2, ' ') ?: " ·",
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(end = 8.dp),
        )
        Column {
          when (loaded) {
            is LoadedLesson.Ok ->
              Text(
                loaded.lesson.title.ifBlank { "(без заголовка)" },
                style = MaterialTheme.typography.bodyMedium,
              )

            is LoadedLesson.Failed ->
              Text(
                "не разбирается",
                color = Color(0xFFB00020),
                style = MaterialTheme.typography.bodyMedium,
              )
          }
          Text(loaded.file.name, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
