package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.JobStatus
import java.nio.file.Path

@Composable
fun HistoryScreen(container: AppContainer) {
  val jobs by container.queue.jobs.collectAsState()
  val done = jobs.filter { it.status == JobStatus.DONE }.reversed()
  val spent = jobs.mapNotNull { it.costUsd }.sum()

  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("История", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "готовых: ${done.size} · всего потрачено: ${formatCost(spent.takeIf { it > 0 })}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { openInSystem(container.outputDir) }) { Text("Открыть папку") }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    if (done.isEmpty()) {
      Text(
        "Готовых результатов пока нет. Файлы складываются в ${container.outputDir}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(done, key = { it.id }) { job -> HistoryRow(job) }
    }
  }
}

@Composable
private fun HistoryRow(job: GenerationJob) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(12.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Thumbnail(job)
      Column(Modifier.weight(1f)) {
        Text(job.request.prompt.take(160), style = MaterialTheme.typography.bodyMedium)
        Text(
          "${job.request.modality.label} · ${job.request.model}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "${job.artifacts.size} файл(ов) · ${formatCost(job.costUsd)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        for (artifact in job.artifacts) {
          val path = Path.of(artifact)
          TextButton(onClick = { openInSystem(path) }) { Text(path.fileName.toString()) }
        }
      }
    }
  }
}

/**
 * Превью только для картинок: кадр из видео потребовал бы декодера, а рисовать «заглушку вместо
 * кадра» честнее, чем притворяться, что превью есть.
 */
@Composable
private fun Thumbnail(job: GenerationJob) {
  val first = job.artifacts.firstOrNull()
  val bitmap = remember(first) {
    if (job.request.modality == GenerationModality.IMAGE && first != null) loadImage(Path.of(first)) else null
  }
  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = job.request.prompt,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
    )
  } else {
    Column(
      Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        when (job.request.modality) {
          GenerationModality.VIDEO -> "видео"
          GenerationModality.AUDIO -> "аудио"
          GenerationModality.IMAGE -> "нет файла"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
