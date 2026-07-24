package lessonviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.io.LoadedLesson
import java.io.File

@Composable
fun LessonPane(loaded: LoadedLesson?, assetsRoot: File?) {
  when (loaded) {
    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Выбери урок слева")
    }

    is LoadedLesson.Failed -> Column(Modifier.fillMaxSize().padding(16.dp)) {
      Text(loaded.file.name, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      ErrorPlate("Урок не разбирается: ${loaded.message}")
    }

    is LoadedLesson.Ok -> LazyColumn(
      Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Column {
          Text(
            loaded.lesson.title.ifBlank { loaded.file.name },
            style = MaterialTheme.typography.headlineSmall,
          )
          if (loaded.lesson.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(loaded.lesson.description, style = MaterialTheme.typography.bodyMedium)
          }
          Spacer(Modifier.height(4.dp))
          Text("шагов: ${loaded.lesson.steps.size}", style = MaterialTheme.typography.labelSmall)
        }
      }
      itemsIndexed(loaded.lesson.steps) { i, step ->
        StepCard(step, i, assetsRoot)
      }
    }
  }
}
