package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.GenerationRequest
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.imageFileToDataUrl
import java.nio.file.Path

@Composable
fun GenerateScreen(container: AppContainer, onOpenSettings: () -> Unit) {
  val apiKey by container.apiKey.collectAsState()
  val jobs by container.queue.jobs.collectAsState()

  var modality by remember { mutableStateOf(GenerationModality.IMAGE) }
  var prompt by remember { mutableStateOf("") }
  var voice by remember { mutableStateOf("") }
  var inputImage by remember { mutableStateOf<Path?>(null) }
  var lastError by remember { mutableStateOf<String?>(null) }

  // Модель — свободный текст с подсказками из каталога: слаги у OpenRouter меняются чаще, чем
  // успевает обновляться список, и устаревшая подсказка не должна мешать ввести актуальную руками.
  var model by remember(modality) {
    mutableStateOf(container.catalog.defaultFor(modality)?.id ?: "")
  }

  Column(Modifier.fillMaxWidth()) {
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

    ModelField(
      model = model,
      suggestions = container.catalog.forModality(modality).map { it.id to it.displayName },
      onModelChange = { model = it },
    )
    Spacer(Modifier.height(12.dp))

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
        suggestions = container.catalog.forModality(modality).flatMap { it.voices }.distinct(),
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
        enabled = prompt.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank(),
        onClick = {
          // data-URL строится здесь, а не в провайдере: чтение файла может упасть (файл удалили,
          // нет прав), и об этом нужно сказать до того, как задание уйдёт в очередь.
          val imageUrl = inputImage?.let { path ->
            runCatching { imageFileToDataUrl(path) }
              .onFailure { lastError = "Не удалось прочитать ${path.fileName}: ${it.message}" }
              .getOrNull()
          }
          if (inputImage != null && imageUrl == null) return@Button
          lastError = null
          container.queue.submit(
            GenerationRequest(
              modality = modality,
              prompt = prompt.trim(),
              model = model.trim(),
              inputImageUrl = imageUrl,
              voice = voice.trim().ifBlank { null },
            ),
          )
        },
      ) { Text("Сгенерировать") }

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

    val active = jobs.filter { !it.status.isTerminal } + jobs.filter { it.status.isTerminal }.takeLast(5)
    if (active.isEmpty()) {
      Text(
        "Пока пусто. Первое задание появится здесь со статусом и стоимостью.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(active, key = { it.id }) { job ->
          JobRow(
            job = job,
            onCancel = { container.queue.cancel(job.id) },
            onRetry = { container.queue.retry(job.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun ModelField(
  model: String,
  suggestions: List<Pair<String, String>>,
  onModelChange: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = model,
      onValueChange = onModelChange,
      label = { Text("Модель") },
      singleLine = true,
      modifier = Modifier.width(520.dp),
    )
    Box {
      OutlinedButton(onClick = { expanded = true }) { Text("Из списка") }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (suggestions.isEmpty()) {
          DropdownMenuItem(text = { Text("нет подсказок для этой модальности") }, onClick = {})
        }
        for ((id, label) in suggestions) {
          DropdownMenuItem(
            text = { Text(if (label == id) id else "$label  ·  $id") },
            onClick = {
              onModelChange(id)
              expanded = false
            },
          )
        }
      }
    }
  }
}

@Composable
private fun VoiceField(voice: String, suggestions: List<String>, onVoiceChange: (String) -> Unit) {
  Column {
    OutlinedTextField(
      value = voice,
      onValueChange = onVoiceChange,
      label = { Text("Голос (можно оставить пустым)") },
      singleLine = true,
      modifier = Modifier.width(320.dp),
    )
    if (suggestions.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in suggestions.take(6)) {
          FilterChip(
            selected = voice == option,
            onClick = { onVoiceChange(if (voice == option) "" else option) },
            label = { Text(option) },
          )
        }
      }
    }
  }
}

@Composable
private fun InputImageField(
  picked: Path?,
  modality: GenerationModality,
  onPick: () -> Unit,
  onClear: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(onClick = onPick) {
      Text(if (modality == GenerationModality.VIDEO) "Картинка → видео" else "Картинка-основа")
    }
    if (picked == null) {
      Text(
        "не выбрана — будет генерация только по тексту",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(picked.fileName.toString(), style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = onClear) { Text("убрать") }
    }
  }
}

@Composable
private fun JobRow(job: GenerationJob, onCancel: () -> Unit, onRetry: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(12.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!job.status.isTerminal) {
        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
      }
      Column(Modifier.weight(1f)) {
        Text(
          "${job.request.modality.label} · ${statusLabel(job.status)}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = if (job.status == JobStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          job.request.prompt.take(120),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (job.error ?: job.note)?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
      }
      Text(formatCost(job.costUsd), style = MaterialTheme.typography.bodySmall)
      if (job.status.isTerminal) {
        TextButton(onClick = onRetry) { Text("Повторить") }
      } else {
        TextButton(onClick = onCancel) { Text("Отменить") }
      }
    }
  }
}

internal fun statusLabel(status: JobStatus): String = when (status) {
  JobStatus.QUEUED -> "в очереди"
  JobStatus.RUNNING -> "генерируется"
  JobStatus.POLLING -> "ждём провайдера"
  JobStatus.DONE -> "готово"
  JobStatus.FAILED -> "ошибка"
  JobStatus.CANCELLED -> "отменено"
}
