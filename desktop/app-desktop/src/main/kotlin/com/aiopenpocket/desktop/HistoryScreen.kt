package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.CostSummary
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.costSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Composable
fun HistoryScreen(container: AppContainer) {
  val jobs by container.queue.jobs.collectAsState()
  val done = jobs.filter { it.status == JobStatus.DONE }.reversed()
  val total = jobs.costSummary()
  val session = jobs.costSummary(sinceEpochMs = container.sessionStartedAtEpochMs)

  // Пачка показывается одной карточкой, одиночное задание — само по себе.
  val groups = done.groupBy { it.batchId ?: it.id }.values.toList()

  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("История", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "готовых: ${done.size}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { openInSystem(container.outputDir) }) { Text("Открыть папку") }
    }
    Spacer(Modifier.height(12.dp))
    CostPanel(total = total, session = session)
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    if (groups.isEmpty()) {
      Text(
        "Готовых результатов пока нет. Файлы складываются в ${container.outputDir}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(groups, key = { it.first().id }) { group ->
        if (group.size == 1) SingleRow(group.single()) else BatchRow(group)
      }
    }
  }
}

@Composable
private fun CostPanel(total: CostSummary, session: CostSummary) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CostFigure("За сессию", session)
        CostFigure("Всего", total)
      }
      if (total.byModality.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
          total.byModality.entries.joinToString("   ") { (modality, sum) ->
            "${modality.label}: ${formatCost(sum)}"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (total.partial) {
        Spacer(Modifier.height(6.dp))
        // Молча показывать неполную сумму как полную — врать про деньги. Провайдер не по каждому
        // ответу отдаёт cost (у озвучки его нет вовсе).
        Text(
          "Это нижняя оценка: по ${total.jobsWithoutCost} заданию(ям) провайдер не сообщил стоимость.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun CostFigure(label: String, summary: CostSummary) {
  Column {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      formatCost(summary.totalUsd.takeIf { summary.jobsWithCost > 0 }),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun SingleRow(job: GenerationJob) {
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
 * Пачка: варианты одного промпта или один промпт по разным моделям — рядом, чтобы их можно было
 * сравнить взглядом. Модель подписана под каждым результатом, иначе сравнение бессмысленно.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchRow(group: List<GenerationJob>) {
  val first = group.first()
  val models = group.map { it.request.model }.distinct()
  val sum = group.mapNotNull { it.costUsd }.sum().takeIf { group.any { job -> job.costUsd != null } }

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Text(first.request.prompt.take(160), style = MaterialTheme.typography.bodyMedium)
      Text(
        buildString {
          append(first.request.modality.label)
          append(" · ")
          append(if (models.size > 1) "сравнение ${models.size} моделей" else "${group.size} варианта(ов)")
          append(" · ")
          append(formatCost(sum))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (job in group) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Thumbnail(job)
            Spacer(Modifier.height(4.dp))
            Text(
              if (models.size > 1) job.request.model.substringAfterLast('/') else formatCost(job.costUsd),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            job.artifacts.firstOrNull()?.let { artifact ->
              TextButton(onClick = { openInSystem(Path.of(artifact)) }) { Text("открыть") }
            }
          }
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
  val isImage = job.request.modality == GenerationModality.IMAGE
  // produceState + IO, а не remember { loadImage(...) }: чтение файла и декодирование прямо в
  // композиции подвешивают окно на каждой карточке — при истории в десяток картинок это видно.
  val bitmap by produceState<ImageBitmap?>(initialValue = null, first, isImage) {
    value = if (isImage && first != null) withContext(Dispatchers.IO) { loadImage(Path.of(first)) } else null
  }
  val ready = bitmap
  if (ready != null) {
    Image(
      bitmap = ready,
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
