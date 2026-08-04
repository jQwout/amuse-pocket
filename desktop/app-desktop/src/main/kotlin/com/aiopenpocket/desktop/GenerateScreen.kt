package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationRequest
import java.io.File

private val VARIANT_OPTIONS = listOf(1, 2, 4)

@Composable
fun GenerateScreen(container: AppContainer, onOpenSettings: () -> Unit) {
  val apiKey by container.apiKey.collectAsState()
  val jobs by container.queue.jobs.collectAsState()
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

  Column(Modifier.fillMaxSize()) {
    Text("Генерация", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (item in GenerationModality.entries) {
        FilterChip(
          selected = modality == item,
          onClick = { modality = item },
          label = { Text(item.label) },
        )
      }
    }
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = compareModels,
        onClick = { compareModels = !compareModels },
        label = { Text("Сравнить модели") },
      )
      if (!compareModels) VariantsField(variants, VARIANT_OPTIONS) { variants = it }
    }
    Spacer(Modifier.height(12.dp))

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
    Spacer(Modifier.height(12.dp))

    PresetPicker(
      presets = prompts.forModality(modality),
      onApply = { prompt = it.applyTo(prompt) },
      onSaveCurrent = { savingPreset = true },
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
      value = prompt,
      onValueChange = { prompt = it },
      label = { Text(if (modality == GenerationModality.AUDIO) "Текст для озвучки" else "Промпт") },
      modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
      minLines = 3,
    )
    Spacer(Modifier.height(12.dp))

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

    Spacer(Modifier.height(16.dp))
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
            container.queue.submitAcrossModels(request, selectedModels.toList())
          } else {
            container.queue.submitBatch(request, variants)
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

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Очередь", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val visible = jobs.filter { !it.status.isTerminal } + jobs.filter { it.status.isTerminal }.takeLast(5)
    if (visible.isEmpty()) {
      Text(
        "Пока пусто. Первое задание появится здесь со статусом и стоимостью.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visible, key = { it.id }) { job ->
          JobRow(
            job = job,
            onCancel = { container.queue.cancel(job.id) },
            onRetry = { container.queue.retry(job.id) },
          )
        }
      }
    }
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
