package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.GenaiModelEntry
import com.aiopenpocket.genai.GenerationJob
import com.aiopenpocket.genai.GenerationModality
import com.aiopenpocket.genai.JobStatus
import com.aiopenpocket.genai.PromptPreset
import java.io.File

/** Модель — свободный текст с подсказками: устаревший слаг в каталоге не должен мешать ввести свой. */
@Composable
fun ModelField(model: String, suggestions: List<GenaiModelEntry>, onModelChange: (String) -> Unit) {
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
      // Высота ограничена: моделей из живого каталога бывает второй десяток, и без предела меню
      // накрывало собой промпт и половину экрана. Сверх предела меню прокручивается само.
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.heightIn(max = 360.dp),
      ) {
        if (suggestions.isEmpty()) {
          DropdownMenuItem(text = { Text("нет подсказок для этой модальности") }, onClick = {})
        }
        for (entry in suggestions) {
          val current = entry.id == model
          DropdownMenuItem(
            text = {
              // Две строки: имя читается, слаг остаётся видимым — именно он уходит в запрос, и
              // подменять его красивым названием нельзя. Одной строкой список превращался в стену
              // почти одинаковых идентификаторов.
              Column {
                Text(
                  entry.displayName,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                  color = if (current) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                )
                Text(
                  entry.id,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            onClick = {
              onModelChange(entry.id)
              expanded = false
            },
          )
        }
      }
    }
  }
}

/**
 * Сравнение моделей: один промпт уходит в несколько моделей сразу. Свободного ввода здесь нет
 * намеренно — набирать руками три слага дольше, чем отметить их в списке.
 */
// FlowRow всё ещё @ExperimentalLayoutApi — единственная причина opt-in в этом файле.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelCompareField(
  suggestions: List<GenaiModelEntry>,
  selected: Set<String>,
  onToggle: (String) -> Unit,
) {
  Column {
    if (suggestions.isEmpty()) {
      Text(
        "Для этой модальности нет подсказок — сравнивать не из чего, выключи режим сравнения.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
      return@Column
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (entry in suggestions) {
        FilterChip(
          selected = entry.id in selected,
          onClick = { onToggle(entry.id) },
          label = { Text(entry.displayName) },
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      when (selected.size) {
        0 -> "не выбрано ни одной модели"
        1 -> "выбрана одна модель — запустится как обычная генерация"
        else -> "${selected.size} модели: один промпт уйдёт в каждую"
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Сколько вариантов одного промпта запустить разом. */
@Composable
fun VariantsField(variants: Int, options: List<Int>, onChange: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Вариантов:", style = MaterialTheme.typography.bodyMedium)
    for (option in options) {
      FilterChip(
        selected = variants == option,
        onClick = { onChange(option) },
        label = { Text(option.toString()) },
      )
    }
  }
}

@Composable
fun PresetPicker(presets: List<PromptPreset>, onApply: (PromptPreset) -> Unit, onSaveCurrent: () -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box {
      OutlinedButton(onClick = { expanded = true }) { Text("Заготовки") }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (presets.isEmpty()) {
          DropdownMenuItem(text = { Text("заготовок нет") }, onClick = {})
        }
        for (preset in presets) {
          DropdownMenuItem(
            text = {
              Column {
                Text(preset.title)
                Text(
                  preset.prompt.take(80),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            onClick = {
              onApply(preset)
              expanded = false
            },
          )
        }
      }
    }
    TextButton(onClick = onSaveCurrent) { Text("Сохранить промпт") }
  }
}

@Composable
fun SavePresetDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var title by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Сохранить как заготовку") },
    text = {
      Column {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Название") },
          singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "Заготовка ляжет в твой файл промптов. Название с тем же смыслом перезапишет старую " +
            "запись, а не создаст дубль.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text("Сохранить") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceField(voice: String, suggestions: List<String>, onVoiceChange: (String) -> Unit) {
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
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
fun InputImageField(
  picked: File?,
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
      Text(picked.name, style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = onClear) { Text("убрать") }
    }
  }
}

@Composable
fun JobRow(job: GenerationJob, onCancel: () -> Unit, onRetry: () -> Unit) {
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
          buildString {
            append(job.request.modality.label)
            append(" · ")
            append(statusLabel(job.status))
            if (job.batchId != null) append(" · в пачке")
          },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = if (job.status == JobStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          job.request.model,
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
