package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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

  // Возможности берутся у ВЫБРАННОЙ модели: у видео они свои у каждой, и общего набора не бывает.
  val videoCaps = modelsForModality.firstOrNull { it.id == model }?.video

  // Ключ по модели: у другой модели другой набор длительностей и разрешений, и сохранять выбор
  // от прежней значило бы отправить значение, которого новая модель не заявляла.
  var lastFrame by remember(model) { mutableStateOf<File?>(null) }
  var duration by remember(model) { mutableStateOf<Int?>(null) }
  var resolution by remember(model) { mutableStateOf<String?>(null) }
  var aspectRatio by remember(model) { mutableStateOf<String?>(null) }

  // Живой каталог приезжает уже после первой отрисовки. Дефолт подставляем только в ПУСТОЕ поле:
  // ключ remember по каталогу стирал бы модель, которую пользователь успел ввести руками.
  LaunchedEffect(catalogSnapshot, modality) {
    if (model.isBlank()) model = catalog.defaultFor(modality)?.id ?: ""
  }
  val readyToSubmit = prompt.isNotBlank() && apiKey.isNotBlank() &&
    if (compareModels) selectedModels.isNotEmpty() else model.isNotBlank()

  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Column(Modifier.weight(0.42f).fillMaxHeight()) {
      // Форма прокручивается, а кнопка запуска — нет: в макете она прижата вниз и видна всегда,
      // иначе на длинной форме приходится доскроллить до неё, чтобы просто нажать.
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (item in GenerationModality.entries) {
            FilterChip(
              selected = modality == item,
              onClick = { modality = item },
              label = { Text(item.label) },
            )
          }
        }

        FieldGroup(if (compareModels) "Модели для сравнения" else "Модель", "слаг уходит в запрос") {
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

      FieldGroup(
        if (modality == GenerationModality.AUDIO) "Что озвучить" else "Опишите кадр",
        "своими словами",
      ) {
        PresetPicker(
          presets = prompts.forModality(modality),
          onApply = { prompt = it.applyTo(prompt) },
          onSaveCurrent = { savingPreset = true },
        )

        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = null,
          placeholder = { Text("Мокрый асфальт, фигура в плаще спиной к камере…") },
          modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
          minLines = 4,
        )

        when (modality) {
          GenerationModality.AUDIO -> VoiceField(
            voice = voice,
            suggestions = modelsForModality.flatMap { it.voices }.distinct(),
            onVoiceChange = { voice = it },
          )

          GenerationModality.IMAGE -> InputImageField(
            picked = inputImage,
            modality = modality,
            onPick = { inputImage = pickImageFile() },
            onClear = { inputImage = null },
          )

          // Кадры показываем только те, что модель заявила: у части видео-моделей их нет вовсе.
          GenerationModality.VIDEO -> {
            if (videoCaps?.supportsFirstFrame != false) {
              FramePicker("Первый кадр", inputImage, { inputImage = pickImageFile() }) { inputImage = null }
            }
            if (videoCaps?.supportsLastFrame == true) {
              FramePicker("Последний кадр", lastFrame, { lastFrame = pickImageFile() }) { lastFrame = null }
            }
            Text(
              "не обязательно — можно только по промпту",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (modality == GenerationModality.VIDEO && !compareModels) {
        FieldGroup("Параметры видео", "только то, что заявила модель") {
          VideoParamsFields(
            caps = videoCaps,
            duration = duration,
            onDuration = { duration = it },
            resolution = resolution,
            onResolution = { resolution = it },
            aspectRatio = aspectRatio,
            onAspectRatio = { aspectRatio = it },
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

      HorizontalDivider()
      Spacer(Modifier.height(12.dp))

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          enabled = readyToSubmit,
          onClick = {
            // В задание кладём ПУТЬ, а не содержимое: чтение файла и base64 на десятки мегабайт
            // подвесили бы обработчик клика, а сам data-URL раздул бы историю на диске. Разворачивает
            // его провайдер на IO-диспетчере, уже перед самим запросом.
            lastError = null
            val video = modality == GenerationModality.VIDEO
            val request = GenerationRequest(
              modality = modality,
              prompt = prompt.trim(),
              model = model.trim(),
              inputImage = inputImage?.toString(),
              voice = voice.trim().ifBlank { null },
              // Параметры видео уходят только с видео: у картинки и озвучки их нет, и слать туда
              // duration значило бы гадать за провайдера.
              lastFrameImage = lastFrame?.toString()?.takeIf { video },
              durationSeconds = duration.takeIf { video },
              resolution = resolution.takeIf { video },
              aspectRatio = aspectRatio.takeIf { video },
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
          canAnimate = container.canAnimate,
          onCancel = { container.queue.cancel(job.id) },
          onRetry = { container.queue.retry(job.id) },
          onRefine = { container.refine(job) },
          onAnimate = { container.animate(job) },
        )
      }
    }
  }
}

/**
 * Три иконки на готовой карточке — это не декор из макета, а то, что ядро уже умеет: повтор,
 * image-to-image с этим же кадром на входе и видео, где кадр становится первым.
 */
@Composable
private fun ResultCard(
  job: GenerationJob,
  canAnimate: Boolean,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onRefine: () -> Unit,
  onAnimate: () -> Unit,
) {
  val done = job.status == JobStatus.DONE && job.artifacts.isNotEmpty()
  Card(Modifier.fillMaxWidth()) {
    Column {
      Box(
        Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Preview(job)
      }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (done) {
          IconAction(Icons.Default.Refresh, "Повторить с тем же промптом", onRetry)
          // Доработка и оживление берут на вход готовый файл — у видео и аудио его как картинку
          // не переиспользовать, поэтому кнопки только у картинок.
          if (job.request.modality == GenerationModality.IMAGE) {
            IconAction(Icons.Default.Edit, "Доработать: этот кадр станет основой", onRefine)
            if (canAnimate) IconAction(Icons.Default.PlayArrow, "Сделать видео из этого кадра", onAnimate)
          }
          Spacer(Modifier.weight(1f))
          Text(
            formatCost(job.costUsd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = { openInSystem(File(job.artifacts.first())) }) { Text("Открыть") }
        } else {
          Text(
            statusLabel(job.status),
            style = MaterialTheme.typography.bodySmall,
            color = if (job.status == JobStatus.FAILED) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 6.dp),
          )
          Spacer(Modifier.weight(1f))
          if (!job.status.isTerminal) {
            TextButton(onClick = onCancel) { Text("Отменить") }
          } else {
            TextButton(onClick = onRetry) { Text("Повторить") }
          }
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

/** Иконка-действие с подсказкой: без подписи ряд иконок иначе читается как ребус. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String, onClick: () -> Unit) {
  TooltipArea(tooltip = {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp, 6.dp))
    }
  }) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
      Icon(
        icon,
        contentDescription = hint,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
