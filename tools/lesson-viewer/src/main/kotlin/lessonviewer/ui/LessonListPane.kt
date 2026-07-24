package lessonviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lessonviewer.io.LoadedLesson

@Composable
fun LessonListPane(
  items: List<LoadedLesson>,
  selected: LoadedLesson?,
  onSelect: (LoadedLesson) -> Unit,
) {
  LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
    items(items) { item ->
      val isSelected = item.file == selected?.file
      Column(
        Modifier
          .fillMaxWidth()
          .background(
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
          )
          .clickable { onSelect(item) }
          .padding(8.dp),
      ) {
        Text(item.file.name, style = MaterialTheme.typography.labelSmall)
        when (item) {
          is LoadedLesson.Ok ->
            Text(
              item.lesson.title.ifBlank { "(без заголовка)" },
              style = MaterialTheme.typography.bodyMedium,
            )

          is LoadedLesson.Failed ->
            Text(
              "не разбирается",
              color = Color(0xFFB00020),
              style = MaterialTheme.typography.bodyMedium,
            )
        }
      }
    }
  }
}
