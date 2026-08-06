package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedButton
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
import java.io.File

@Composable
fun HistoryScreen(
  container: AppContainer,
  /** Уводит на экран генерации. null — некуда (offscreen-снимок), кнопка тогда не рисуется. */
  onOpenGenerate: (() -> Unit)? = null,
) {
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
      // Тот же вид, что на «Генерации»: одно действие не должно выглядеть двумя разными кнопками.
      OutlinedButton(onClick = { openInSystem(container.outputDir) }, shape = ButtonShape) {
        Text("Открыть папку")
      }
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
        val reuse = onOpenGenerate?.let { open ->
          { job: GenerationJob ->
            container.loadDraftFrom(job)
            open()
          }
        }
        if (group.size == 1) SingleRow(group.single(), reuse) else BatchRow(group, reuse)
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

/**
 * «В генерацию» — перенести это задание обратно в форму и править дальше.
 *
 * Отдельно от «Повторить»: тот делает точную копию не спрашивая, а сюда жмут, чтобы поменять слово в
 * промпте или подложить другой файл. Без этой кнопки удачный кадр приходилось перенабирать руками,
 * глядя в соседнюю вкладку и вспоминая, какие у файлов были роли.
 */
@Composable
private fun ReuseButton(onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, shape = ButtonShape) { Text("В генерацию") }
}

/**
 * Чем кормили модель: исходные файлы с ролями и тегами.
 *
 * Их не показывали вовсе, хотя в задании они лежат (`GenerationRequest.attachments`). Без них
 * история отвечала на «что я просил», но не на «из чего это сделано», — а для картинки, собранной
 * из фона, персонажа и стилевого референса, второе и есть главное.
 *
 * Миниатюры настоящие: файл-исходник живёт у пользователя и после генерации. Пропал — полосатая
 * заглушка, как и везде, но имя с ролью остаются: по ним видно, чего не хватает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceFiles(job: GenerationJob) {
  val attachments = job.request.attachments
  if (attachments.isEmpty()) return

  FlowRow(
    Modifier.padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    for (attachment in attachments) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SourceThumb(attachment.path)
        Text(
          buildString {
            append(attachment.role.label.lowercase()).append(": ").append(attachment.name)
            if (attachment.tags.isNotEmpty()) append(" (").append(attachment.tags.joinToString(", ")).append(")")
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SourceThumb(path: String) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
    value = withContext(Dispatchers.IO) { loadImage(File(path)) }
  }
  Box(
    Modifier.size(28.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    bitmap?.let {
      Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
  }
}

@Composable
private fun SingleRow(job: GenerationJob, onReuse: ((GenerationJob) -> Unit)?) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(12.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Thumbnail(job)
      Column(Modifier.weight(1f)) {
        // Промпт целиком, а не первые 160 символов. История нужна затем, чтобы повторить или понять
        // удачный кадр, — а обрезанный промпт для этого бесполезен ровно так же, как его отсутствие.
        Text(job.request.prompt.ifBlank { "без промпта" }, style = MaterialTheme.typography.bodyMedium)
        Text(
          "${job.request.modality.label} · ${job.request.model}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceFiles(job)
        Text(
          "${job.artifacts.size} файл(ов) · ${formatCost(job.costUsd)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        onReuse?.let { reuse -> ReuseButton { reuse(job) } }
        for (artifact in job.artifacts) {
          val path = File(artifact)
          TextButton(onClick = { openInSystem(path) }) { Text(path.name) }
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
private fun BatchRow(group: List<GenerationJob>, onReuse: ((GenerationJob) -> Unit)?) {
  val first = group.first()
  val models = group.map { it.request.model }.distinct()
  val sum = group.mapNotNull { it.costUsd }.sum().takeIf { group.any { job -> job.costUsd != null } }

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Text(first.request.prompt.ifBlank { "без промпта" }, style = MaterialTheme.typography.bodyMedium)
      SourceFiles(first)
      Text(
        buildString {
          append(first.request.modality.label)
          append(" · ")
          append(if (models.size > 1) "сравнение ${models.size} моделей" else "${group.size} варианта(ов)")
          append(" · ")
          append(formatCost(sum))
          // «по N из M», когда цену сообщили не все задания пачки: та же честность, что у
          // CostSummary.partial, только строкой ниже. Без пометки сумма двух из четырёх выглядит
          // как полная стоимость пачки.
          val withCost = group.count { it.costUsd != null }
          if (withCost < group.size) append(" (по $withCost из ${group.size})")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // У пачки запрос один на всех, поэтому кнопка одна: брать за основу «второй вариант из
      // четырёх» бессмысленно — промпт, модель и параметры у них те же самые.
      onReuse?.let { reuse ->
        Spacer(Modifier.height(8.dp))
        ReuseButton { reuse(first) }
      }
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
              TextButton(onClick = { openInSystem(File(artifact)) }) { Text("открыть") }
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
    value = if (isImage && first != null) withContext(Dispatchers.IO) { loadImage(File(first)) } else null
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
