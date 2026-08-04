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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.AccessCheck

@Composable
fun SettingsScreen(container: AppContainer) {
  val savedKey by container.apiKey.collectAsState()
  val prompts by container.prompts.collectAsState()
  val restricted by container.keyRestrictedToOwner.collectAsState()
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
        // «Права 600» обещать нельзя: на Windows их не существует и сузить их нечем. Пишем то,
        // что произошло на самом деле, — иначе экран врёт про защиту ключа.
        saved && restricted == true -> "Сохранён в ${container.configDir}/openrouter.key (права 600)"
        saved && restricted == false ->
          "Сохранён в ${container.configDir}/openrouter.key. Сузить права файла эта система не умеет " +
            "(на Windows POSIX-прав нет): ключ защищён только правами профиля."
        saved -> "Сохранён в ${container.configDir}/openrouter.key"
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

    Spacer(Modifier.height(16.dp))
    AccessCheckRow(container)

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("Куда что кладётся", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    PathRow("Результаты", container.outputDir.toString()) { openInSystem(container.outputDir) }
    PathRow("Конфиг и ключ", container.configDir.toString()) { openInSystem(container.configDir) }
    Spacer(Modifier.height(16.dp))
    CatalogRow(container)
    Spacer(Modifier.height(12.dp))
    Text(
      "Свой список моделей — ${container.modelsOverrideFile}. Он задаёт порядок, подписи и голоса " +
        "и остаётся страховкой, когда живого списка нет; модель всегда можно ввести и руками.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Свои заготовки промптов — ${container.promptsUserFile}. Этот файл, в отличие от моделей, " +
        "не заменяет встроенные заготовки, а дополняет их.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Загружено заготовок: ${prompts.presets.size}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Откуда взялся список моделей. Разница «список провайдера» и «встроенные подсказки» показывается
 * прямо: по неавторитетному списку нельзя заключить, что модели больше нет, и пользователь должен
 * понимать, что видит запасной вариант, а не актуальный каталог OpenRouter.
 */
@Composable
private fun CatalogRow(container: AppContainer) {
  val snapshot by container.catalog.collectAsState()
  val refreshing by container.refreshingCatalog.collectAsState()
  val failed by container.catalogRefreshFailed.collectAsState()

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(enabled = !refreshing, onClick = { container.refreshCatalog() }) {
      Text(if (refreshing) "Обновляю…" else "Обновить каталог")
    }
    Column {
      Text(
        "Моделей ${snapshot.catalog.models.size}: " +
          if (snapshot.authoritative) "список OpenRouter" else "встроенные подсказки",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        when {
          failed -> "Обновить не удалось — сеть или OpenRouter не ответили. Список остался прежним."
          snapshot.authoritative -> "Скачан с /api/v1/models и закеширован; обновляется раз в сутки сам."
          else -> "Живой список пока не получен — показываем встроенный. По нему нельзя судить, " +
            "что модель снята с обслуживания."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (snapshot.authoritative && !failed) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.error
        },
      )
    }
  }
}

/**
 * Проверка связи до первой генерации. Без неё непонятный отказ на платном запросе — единственный
 * способ узнать, что ключ не тот или сеть закрыта.
 */
@Composable
private fun AccessCheckRow(container: AppContainer) {
  val result by container.accessCheck.collectAsState()
  val checking by container.checkingAccess.collectAsState()
  val key by container.apiKey.collectAsState()

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(enabled = key.isNotBlank() && !checking, onClick = { container.checkAccess() }) {
      Text(if (checking) "Проверяю…" else "Проверить связь")
    }
    when (val outcome = result) {
      null -> Text(
        if (key.isBlank()) "сначала сохрани ключ" else "связь ещё не проверялась",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      is AccessCheck.Ok -> Column {
        Text("Связь есть, ключ принят", style = MaterialTheme.typography.bodyMedium)
        Text(
          buildString {
            outcome.label?.let { append(it).append(" · ") }
            append("потрачено ")
            append(formatCost(outcome.usageUsd))
            outcome.limitUsd?.let { append(" из ").append(formatCost(it)) }
            outcome.rateLimit?.let { append(" · лимит ").append(it) }
            if (outcome.freeTier == true) append(" · бесплатный тариф")
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      is AccessCheck.Failed -> Text(
        outcome.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
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
