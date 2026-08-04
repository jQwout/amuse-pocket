package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationRequest
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.humanModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val VARIANT_OPTIONS = listOf(1, 2, 4)

/**
 * Две панели: слева форма, справа результаты последнего запуска.
 *
 * Раньше результат жил только на вкладке «История», и после нажатия «Сгенерировать» экран не менялся
 * вовсе — приходилось угадывать, случилось ли что-нибудь, и уходить смотреть в другое место.
 */
@Composable
fun GenerateScreen(container: AppContainer, onOpenSettings: () -> Unit) {
  val apiKey by container.apiKey.collectAsState()
  val prompts by container.prompts.collectAsState()

  var modality by remember { mutableStateOf(GenerationModality.IMAGE) }
  var prompt by remember { mutableStateOf("") }
  var voice by remember { mutableStateOf("") }
  var inputImage by remember { mutableStateOf<File?>(null) }
  var lastError by remember { mutableStateOf<String?>(null) }
  var variants by remember { mutableStateOf(1) }
  var compareModels by remember { mutableStateOf(false) }
  var savingPreset by remember { mutableStateOf(false) }

  val catalogSnapshot by container.catalog.collectAsState()
  val catalog = catalogSnapshot.catalog

  // Модель и набор для сравнения сбрасываются при смене модальности: видео-модель в аудио бессмысленна.
  var model by remember(modality) { mutableStateOf(catalog.defaultFor(modality)?.id ?: "") }
  var selectedModels by remember(modality) { mutableStateOf(emptySet<String>()) }

  val modelsForModality = catalog.forModality(modality)

  // Живой каталог приезжает уже после первой отрисовки. Дефолт подставляем только в ПУСТОЕ поле:
  // ключ remember по каталогу стирал бы модель, которую пользователь успел ввести руками.
  LaunchedEffect(catalogSnapshot, modality) {
    if (model.isBlank()) model = catalog.defaultFor(modality)?.id ?: ""
  }
  val readyToSubmit = prompt.isNotBlank() && apiKey.isNotBlank() &&
    if (compareModels) selectedModels.isNotEmpty() else model.isNotBlank()

  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    // Форма прокручивается сама: панель результатов рядом, и обрезать её ради длинной формы нельзя.
    Column(
      Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        when (modality) {
          GenerationModality.IMAGE -> "Генерация картинки"
          GenerationModality.VIDEO -> "Генерация видео"
          GenerationModality.AUDIO -> "Озвучка текста"
        },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )

      SectionCard("Что генерируем") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (item in GenerationModality.entries) {
            FilterChip(
              selected = modality == item,
              onClick = { modality = item },
              label = { Text(item.label) },
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          FilterChip(
            selected = compareModels,
            onClick = { compareModels = !compareModels },
            label = { Text("Сравнить модели") },
          )
          if (!compareModels) VariantsField(variants, VARIANT_OPTIONS) { variants = it }
        }
      }

      SectionCard(if (compareModels) "Модели для сравнения" else "Модель") {
        if (compareModels) {
          ModelCompareField(
            suggestions = modelsForModality,
            selected = selectedModels,
            onToggle = { id ->
              selectedModels = if (id in selectedModels) selectedModels - id else selectedModels + id
            },
          )
        } else {
          ModelField(model = model, suggestions = modelsForModality) { model = it }
        }
      }

      SectionCard(if (modality == GenerationModality.AUDIO) "Текст для озвучки" else "Промпт") {
        PresetPicker(
          presets = prompts.forModality(modality),
          onApply = { prompt = it.applyTo(prompt) },
          onSaveCurrent = { savingPreset = true },
        )

        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = { Text(if (modality == GenerationModality.AUDIO) "Что озвучить" else "Что нарисовать") },
          modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
          minLines = 4,
        )

        when (modality) {
          GenerationModality.AUDIO -> VoiceField(
            voice = voice,
            suggestions = modelsForModality.flatMap { it.voices }.distinct(),
            onVoiceChange = { voice = it },
          )

          GenerationModality.IMAGE, GenerationModality.VIDEO -> InputImageField(
            picked = inputImage,
            modality = modality,
            onPick = { inputImage = pickImageFile() },
            onClear = { inputImage = null },
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          enabled = readyToSubmit,
          onClick = {
            // В задание кладём ПУТЬ, а не содержимое: чтение файла и base64 на десятки мегабайт
            // подвесили бы обработчик клика, а сам data-URL раздул бы историю на диске. Разворачивает
            // его провайдер на IO-диспетчере, уже перед самим запросом.
            lastError = null
            val request = GenerationRequest(
              modality = modality,
              prompt = prompt.trim(),
              model = model.trim(),
              inputImage = inputImage?.toString(),
              voice = voice.trim().ifBlank { null },
            )
            if (compareModels) {
              container.submitAcrossModels(request, selectedModels.toList())
            } else {
              container.submitBatch(request, variants)
            }
          },
        ) {
          Text(
            when {
              compareModels -> "Сравнить (${selectedModels.size})"
              variants > 1 -> "Сгенерировать ×$variants"
              else -> "Сгенерировать"
            },
          )
        }

        if (apiKey.isBlank()) {
          TextButton(onClick = onOpenSettings) { Text("Сначала укажи ключ OpenRouter") }
        }
        lastError?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    VerticalDivider()

    ResultsPane(container, Modifier.weight(0.58f).fillMaxHeight())
  }

  if (savingPreset) {
    SavePresetDialog(
      onDismiss = { savingPreset = false },
      onConfirm = { title ->
        if (!container.savePreset(title, prompt, modality)) {
          lastError = "Не удалось сохранить заготовку в ${container.promptsUserFile}"
        }
        savingPreset = false
      },
    )
  }
}

/**
 * Результаты последнего запуска — вся пачка целиком, в каком бы статусе ни были задания.
 *
 * Задания берутся по id из контейнера и сопоставляются с ЖИВЫМ списком очереди, а не хранятся
 * копией: статус, стоимость и пути к файлам меняются по ходу дела.
 */
@Composable
private fun ResultsPane(container: AppContainer, modifier: Modifier = Modifier) {
  val jobs by container.queue.jobs.collectAsState()
  val lastIds by container.lastSubmitted.collectAsState()

  val batch = lastIds.mapNotNull { id -> jobs.firstOrNull { it.id == id } }
  // Задания вне текущей пачки иначе пропали бы с глаз, а среди них может быть оплаченное видео.
  val otherActive = jobs.count { !it.status.isTerminal && it.id !in lastIds }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          "Результаты",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        val subtitle = when {
          batch.isEmpty() -> "последнего запуска пока не было"
          else -> "${batch.size} шт. · " + humanModelName(batch.first().request.model)
        }
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { openInSystem(container.outputDir) }) { Text("Открыть папку") }
    }

    if (otherActive > 0) {
      Text(
        "ещё $otherActive задание(й) в работе вне этой пачки",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (batch.isEmpty()) {
      Text(
        "Запусти генерацию — результаты появятся здесь, рядом с формой.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    LazyVerticalGrid(
      // Adaptive, а не фиксированные две колонки: на узком окне карточки не должны схлопываться
      // в нечитаемые полоски.
      columns = GridCells.Adaptive(minSize = 240.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(batch, key = { it.id }) { job ->
        ResultCard(
          job = job,
          onCancel = { container.queue.cancel(job.id) },
          onRetry = { container.queue.retry(job.id) },
        )
      }
    }
  }
}

@Composable
private fun ResultCard(job: GenerationJob, onCancel: () -> Unit, onRetry: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column {
      Box(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Preview(job)
      }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (job.status == JobStatus.DONE) formatCost(job.costUsd) else statusLabel(job.status),
          style = MaterialTheme.typography.bodySmall,
          color = if (job.status == JobStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
        when {
          !job.status.isTerminal -> TextButton(onClick = onCancel) { Text("Отменить") }
          job.artifacts.isNotEmpty() ->
            TextButton(onClick = { openInSystem(File(job.artifacts.first())) }) { Text("Открыть") }

          else -> TextButton(onClick = onRetry) { Text("Повторить") }
        }
      }
      job.error?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
        )
      }
    }
  }
}

/**
 * Превью только для картинок. Кадр из видео потребовал бы декодера, и рисовать честную заглушку
 * с подписью «видео» правильнее, чем притворяться, что превью есть.
 */
@Composable
private fun Preview(job: GenerationJob) {
  val first = job.artifacts.firstOrNull()
  val isImage = job.request.modality == GenerationModality.IMAGE
  // produceState + IO: чтение и декодирование файла прямо в композиции подвешивают окно.
  val bitmap by produceState<ImageBitmap?>(initialValue = null, first, isImage) {
    value = if (isImage && first != null) withContext(Dispatchers.IO) { loadImage(File(first)) } else null
  }

  val ready = bitmap
  when {
    ready != null -> Image(
      bitmap = ready,
      contentDescription = job.request.prompt,
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
    )

    !job.status.isTerminal -> Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
      Text(
        job.note ?: statusLabel(job.status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    else -> Text(
      when (job.status) {
        JobStatus.DONE -> job.request.modality.label
        else -> statusLabel(job.status)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
