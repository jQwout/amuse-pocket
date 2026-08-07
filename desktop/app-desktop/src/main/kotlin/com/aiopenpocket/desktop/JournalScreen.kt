package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.genai.JournalEntry
import com.aiopenpocket.genai.formatUsd
import com.aiopenpocket.genai.pluralRu
import com.aiopenpocket.genai.prettyJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Журнал запросов: что ушло к провайдеру и что он ответил.
 *
 * Отладочный экран, поэтому здесь нет ни украшений, ни пересказа своими словами: тела показываются
 * как есть, моноширинным шрифтом. Единственное, чего в них нет, — ключа и мегабайтов base64, и
 * убраны они не здесь, а на входе в журнал (см. `journalSafe`): чистить при показе значило бы
 * держать в памяти сырое тело и надеяться, что его никто не покажет мимо чистилки.
 *
 * Экран доступен только когда журнал включён в настройках, поэтому «пусто» здесь означает ровно
 * одно — запросов ещё не было.
 */
@Composable
fun JournalScreen(container: AppContainer) {
  val entries by container.requestJournal.entries.collectAsState()
  var selectedId by remember { mutableStateOf<String?>(null) }
  val selected = entries.firstOrNull { it.id == selectedId } ?: entries.firstOrNull()

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Журнал запросов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Text(
        "${entries.size} ${pluralRu(entries.size, "запрос", "запроса", "запросов")} · только в памяти",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = container::clearRequestJournal, shape = ButtonShape) { Text("Очистить") }
    }

    if (entries.isEmpty()) {
      Text(
        "Запросов ещё не было. Журнал включён — сгенерируй кадр, и он появится здесь целиком: " +
          "тело запроса, ответ провайдера и цена.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }

    Row(Modifier.fillMaxSize()) {
      LazyColumn(Modifier.width(340.dp).fillMaxHeight()) {
        items(entries, key = { it.id }) { entry ->
          EntryRow(entry, active = entry.id == selected?.id) { selectedId = entry.id }
        }
      }
      VerticalDivider(color = MaterialTheme.colorScheme.outline)
      selected?.let { EntryDetails(it, Modifier.weight(1f).fillMaxHeight()) }
    }
  }
}

@Composable
private fun EntryRow(entry: JournalEntry, active: Boolean, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        formatTime(entry.atEpochMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        entry.status?.toString() ?: "нет ответа",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        // Код ответа — первое, что ищут в журнале: отказ должен быть виден, не читая строку.
        color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      )
      Text(
        "${entry.method} · ${entry.durationMs} мс",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      entry.costUsd?.let {
        Spacer(Modifier.weight(1f))
        Text(formatUsd(it), style = MaterialTheme.typography.labelSmall)
      }
    }
    Text(
      entry.model.ifBlank { entry.url.substringAfterLast('/') },
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun EntryDetails(entry: JournalEntry, modifier: Modifier) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("${entry.method} ${entry.url}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(
      buildString {
        append(entry.modality.label)
        if (entry.model.isNotBlank()) append(" · ${entry.model}")
        append(" · ${entry.durationMs} мс")
        entry.costUsd?.let { append(" · ${formatUsd(it)}") }
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    entry.error?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    BodyBlock("ЗАПРОС", entry.requestBody)
    BodyBlock("ОТВЕТ", entry.responseBody)
  }
}

/**
 * Тело моноширинным шрифтом и с отступами.
 *
 * Отступы важнее, чем кажется: провайдер шлёт и присылает одну длинную строку, и в исходном виде
 * тело читается только горизонтальной прокруткой — всё, что не влезло в ширину, приходится искать
 * вслепую, включая поле с референсами, ради которого в журнал чаще всего и лезут. Прокрутка вбок
 * при этом остаётся: она нужна редким длинным строкам внутри уже разложенного JSON.
 *
 * Копируется то же, что показано: разложенный текст, а не исходная строка. Журнал открывают, чтобы
 * приложить кусок к вопросу, и приложить хочется читаемое.
 */
@Composable
private fun BodyBlock(title: String, rawBody: String) {
  val clipboard = LocalClipboardManager.current
  val body = remember(rawBody) { prettyJson(rawBody) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (body.isNotBlank()) {
        Text(
          "Скопировать",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable { clipboard.setText(AnnotatedString(body)) },
        )
      }
    }
    Surface(
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        body.ifBlank { "пусто" },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
      )
    }
    HorizontalDivider(color = Color.Transparent)
  }
}

/**
 * Время без даты: журнал живёт в памяти процесса, то есть все записи — сегодняшние и в пределах
 * одного запуска. Дата тут была бы шумом.
 */
private fun formatTime(epochMs: Long): String =
  TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
