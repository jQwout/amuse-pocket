package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(container: AppContainer) {
  val savedKey by container.apiKey.collectAsState()
  var draft by remember(savedKey) { mutableStateOf(savedKey) }
  var reveal by remember { mutableStateOf(false) }
  var saved by remember { mutableStateOf(false) }

  Column(Modifier.fillMaxWidth()) {
    Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(16.dp))

    Text("Ключ OpenRouter", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = draft,
        onValueChange = {
          draft = it
          saved = false
        },
        label = { Text("sk-or-…") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.width(520.dp),
      )
      TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Скрыть" else "Показать") }
      Button(
        enabled = draft.isNotBlank() && draft != savedKey,
        onClick = {
          container.saveApiKey(draft)
          saved = true
        },
      ) { Text("Сохранить") }
    }
    Spacer(Modifier.height(6.dp))
    Text(
      when {
        saved -> "Сохранён в ${container.configDir}/openrouter.key (права 600)"
        savedKey.isBlank() -> "Ключ не задан — генерация не запустится"
        else -> "Ключ сохранён в ${container.configDir}/openrouter.key"
      },
      style = MaterialTheme.typography.bodySmall,
      color = if (savedKey.isBlank() && !saved) {
        MaterialTheme.colorScheme.error
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    )

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("Куда что кладётся", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    PathRow("Результаты", container.outputDir.toString()) { openInSystem(container.outputDir) }
    PathRow("Конфиг и ключ", container.configDir.toString()) { openInSystem(container.configDir) }
    Spacer(Modifier.height(12.dp))
    Text(
      "Список моделей можно переопределить файлом ${container.modelsOverrideFile} — " +
        "он перекрывает встроенный, так что новые модели OpenRouter добавляются без пересборки.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Загружено подсказок моделей: ${container.catalog.models.size}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PathRow(label: String, path: String, onOpen: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("$label:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onOpen) { Text("открыть") }
  }
}
