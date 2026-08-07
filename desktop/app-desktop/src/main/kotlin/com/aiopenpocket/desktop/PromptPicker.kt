package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aiopenpocket.genai.OTHER_CATEGORY
import com.aiopenpocket.genai.PromptCard
import com.aiopenpocket.genai.PromptPreset
import com.aiopenpocket.genai.PromptScope
import com.aiopenpocket.genai.PromptSort
import com.aiopenpocket.genai.inScope
import com.aiopenpocket.genai.matches
import com.aiopenpocket.genai.pluralRu
import com.aiopenpocket.genai.promptCategories
import com.aiopenpocket.genai.promptSummary
import com.aiopenpocket.genai.scopeCount
import com.aiopenpocket.genai.sortPromptCards
import com.aiopenpocket.genai.usesLabel

/**
 * Окно заготовок промптов.
 *
 * Пришло на смену выпадающему меню: в списке из сорока с лишним заготовок меню бесполезно — в нём не
 * видно ни текста заготовки, ни того, что в ней надо подставить, а именно это и решает, годится она
 * или нет. Раскладка та же, что у выбора модели, и это не подражание: задача одна — выбрать из
 * длинного списка, глядя на содержимое, а не на название.
 *
 * `usePlatformDefaultWidth = false` обязателен — ровно та же грабля, что в [ModelPickerDialog]: без
 * него Dialog зажимает содержимое платформенной шириной около 640 px.
 */
@Composable
fun PromptPickerDialog(
  cards: List<PromptCard>,
  /** Что уже набрано в форме: с этого начинается «Новая заготовка». */
  currentPrompt: String,
  onDismiss: () -> Unit,
  onApply: (PromptPreset, Boolean) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onSave: (PromptPreset) -> Unit,
  onDelete: (String) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    PromptPickerPanel(cards, currentPrompt, onDismiss, onApply, onToggleFavorite, onSave, onDelete)
  }
}

/** Тело окна отдельно от окна — ради offscreen-снимка, как и у выбора модели. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptPickerPanel(
  cards: List<PromptCard>,
  /**
   * Набранный промпт. «Новая заготовка» начинается с него, а не с пустого поля: сохранить удачную
   * формулировку — самый частый повод завести свою заготовку, и заставлять перенабирать её руками
   * было бы издевательством. Отдельной кнопки «Сохранить промпт» на экране больше нет — её работу
   * делает эта.
   */
  currentPrompt: String = "",
  onDismiss: () -> Unit = {},
  onApply: (PromptPreset, Boolean) -> Unit = { _, _ -> },
  onToggleFavorite: (String) -> Unit = {},
  onSave: (PromptPreset) -> Unit = {},
  onDelete: (String) -> Unit = {},
) {
  var query by remember { mutableStateOf("") }
  var scope by remember { mutableStateOf(PromptScope.ALL) }
  var category by remember { mutableStateOf<String?>(null) }
  var sort by remember { mutableStateOf(PromptSort.POPULAR) }
  var highlighted by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf<PromptPreset?>(null) }
  var confirmDelete by remember { mutableStateOf<PromptCard?>(null) }

  val visible = remember(cards, query, scope, category, sort) {
    sortPromptCards(
      cards.filter { it.matches(query) && it.inScope(scope) && (category == null || it.category == category) },
      sort,
    )
  }
  val shown = cards.firstOrNull { it.id == highlighted } ?: visible.firstOrNull()

  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier
      .fillMaxWidth(0.94f)
      .fillMaxHeight(0.92f)
      .widthIn(max = 1180.dp)
      .heightIn(max = 800.dp)
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
          onDismiss()
          true
        } else {
          false
        }
      },
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text("Заготовки промптов", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("Название, текст промпта или тег") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp)) },
          singleLine = true,
          shape = ButtonShape,
          modifier = Modifier.weight(1f),
        )
        Text(
          "${cards.size} ${pluralRu(cards.size, "промпт", "промпта", "промптов")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Закрыть", Modifier.size(18.dp))
        }
      }

      // Разделы и категории в одном ряду: они отвечают на разные вопросы («откуда» и «о чём»), но
      // выбирают одно и то же — что показать слева, и разводить их по двум рядам значит тратить
      // высоту на объяснение, которого никто не просил.
      FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          "КАТЕГОРИЯ",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 10.dp, end = 4.dp),
        )
        for (option in PromptScope.entries) {
          CountedChip(
            label = option.label,
            count = scopeCount(cards, option),
            selected = scope == option && category == null,
            onClick = {
              scope = option
              category = null
            },
          )
        }
        for ((name, count) in promptCategories(cards)) {
          CountedChip(
            label = name,
            count = count,
            selected = category == name,
            onClick = {
              category = if (category == name) null else name
              scope = PromptScope.ALL
            },
          )
        }
      }

      Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "СОРТИРОВКА",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (option in PromptSort.entries) {
          FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
          onClick = {
            editing = PromptPreset(id = "", title = "", prompt = currentPrompt, category = OTHER_CATEGORY)
          },
          shape = ButtonShape,
        ) {
          Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Новая заготовка")
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)

      Row(Modifier.weight(1f)) {
        if (visible.isEmpty()) {
          Text(
            if (query.isBlank()) "Здесь пока пусто." else "Ничего не нашлось по «$query».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(20.dp),
          )
        } else {
          LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(visible, key = { it.id }) { card ->
              PromptCardView(
                card = card,
                active = card.id == shown?.id,
                onClick = { highlighted = card.id },
                onToggleFavorite = { onToggleFavorite(card.id) },
              )
            }
          }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        PromptDetails(
          card = shown,
          onApply = onApply,
          onToggleFavorite = onToggleFavorite,
          onEdit = { editing = it },
          onDuplicate = { source ->
            editing = source.copy(id = "", title = source.title + " (копия)")
          },
          onDelete = { confirmDelete = it },
        )
      }
    }
  }

  editing?.let { preset ->
    PromptEditorDialog(
      preset = preset,
      categories = promptCategories(cards).map { it.first },
      onDismiss = { editing = null },
      onSave = {
        onSave(it)
        highlighted = it.id
        editing = null
      },
    )
  }

  confirmDelete?.let { card ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("Удалить «${card.title}»?") },
      text = { Text("Заготовка исчезнет из твоего файла. Встроенные заготовки этим не тронуть.") },
      confirmButton = {
        TextButton(onClick = {
          onDelete(card.id)
          confirmDelete = null
        }) { Text("Удалить") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
    )
  }
}

/** Чип с числом: сколько заготовок за ним стоит. Ноль показываем тоже — «пусто» это тоже ответ. */
@Composable
private fun CountedChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Text(
          count.toString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
  )
}

@Composable
private fun PromptCardView(card: PromptCard, active: Boolean, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      1.dp,
      if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          card.title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Icon(
          if (card.favorite) Icons.Default.Star else Icons.Default.StarBorder,
          contentDescription = "в избранное",
          tint = if (card.favorite) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.outline
          },
          modifier = Modifier.size(16.dp).clickable(onClick = onToggleFavorite),
        )
      }
      // Текст заготовки прямо в карточке: половина из них отличается друг от друга не названием,
      // а формулировкой, и выбирают именно по ней.
      Text(
        highlightPlaceholders(card.preset.prompt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      usesLabel(card.uses).takeIf { it.isNotBlank() }?.let {
        Text(
          it,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.align(Alignment.End),
        )
      }
    }
  }
}

@Composable
private fun PromptDetails(
  card: PromptCard?,
  onApply: (PromptPreset, Boolean) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onEdit: (PromptPreset) -> Unit,
  onDuplicate: (PromptPreset) -> Unit,
  onDelete: (PromptCard) -> Unit,
) {
  Column(Modifier.width(380.dp).fillMaxHeight()) {
    if (card == null) {
      Text(
        "Выбери заготовку слева",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
      )
      return@Column
    }

    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      if (card.preset.tags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          card.preset.tags.forEach { tag ->
            Surface(
              shape = MaterialTheme.shapes.extraSmall,
              color = Color.Transparent,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
              Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
        }
      }
      Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
          highlightPlaceholders(card.preset.prompt),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(12.dp),
        )
      }
      Text(
        promptSummary(card),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(onClick = { onApply(card.preset, false) }, shape = ButtonShape) { Text("Вставить") }
      TextButton(onClick = { onApply(card.preset, true) }) { Text("Дописать") }
      Spacer(Modifier.weight(1f))
      IconAction(
        if (card.favorite) Icons.Default.Star else Icons.Default.StarBorder,
        "В избранное",
      ) { onToggleFavorite(card.id) }
      // Править и удалять можно только свои: встроенные лежат в jar, и «сохранить» поверх них
      // означало бы завести копию, о которой пользователь не просил.
      if (card.own) {
        IconAction(Icons.Default.Edit, "Править") { onEdit(card.preset) }
      }
      IconAction(Icons.Default.ContentCopy, "Сделать копию") { onDuplicate(card.preset) }
      if (card.own) {
        IconAction(Icons.Default.DeleteOutline, "Удалить") { onDelete(card) }
      }
    }
  }
}

/**
 * Редактор заготовки. Новая — с пустым id: он считается по заголовку при сохранении. У правки id
 * приходит готовым, иначе переименование плодило бы вторую запись вместо изменения первой.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptEditorDialog(
  preset: PromptPreset,
  categories: List<String>,
  onDismiss: () -> Unit,
  onSave: (PromptPreset) -> Unit,
) {
  var title by remember { mutableStateOf(preset.title) }
  var text by remember { mutableStateOf(preset.prompt) }
  var category by remember { mutableStateOf(preset.category.ifBlank { OTHER_CATEGORY }) }
  var tags by remember { mutableStateOf(preset.tags.joinToString(", ")) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (preset.id.isBlank()) "Новая заготовка" else "Правка заготовки") },
    text = {
      Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Название") },
          singleLine = true,
          shape = ButtonShape,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("Текст промпта") },
          // Точная высота, а не минимум: поле в диалоге росло бы вниз и выталкивало кнопки за край —
          // та же беда, что была у промпта на экране генерации.
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 160.dp),
          shape = ButtonShape,
        )
        Text(
          "В фигурных скобках — то, что подставляется под кадр: {объект}, {цвет фона}. " +
            "Особый случай — {основной промпт}: заготовка с ним не заменяет набранное, а оборачивает его.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = tags,
          onValueChange = { tags = it },
          label = { Text("Теги через запятую") },
          singleLine = true,
          shape = ButtonShape,
          modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (name in (categories + OTHER_CATEGORY).distinct()) {
            FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) })
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            preset.copy(
              id = preset.id.ifBlank { com.aiopenpocket.genai.PromptLibrary.idFor(title) },
              title = title.trim(),
              prompt = text.trim(),
              category = category,
              tags = tags.split(',').map { it.trim() }.filter { it.isNotBlank() },
            ),
          )
        },
        shape = ButtonShape,
        enabled = title.isNotBlank() && text.isNotBlank(),
      ) { Text("Сохранить") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

/**
 * Подсветка того, что подставляется под кадр. Не украшение: без неё `{объект}` теряется в тексте,
 * и заготовку отправляют в запрос как есть — модель честно рисует слово «объект».
 */
@Composable
private fun highlightPlaceholders(prompt: String): AnnotatedString {
  val accent = MaterialTheme.colorScheme.primary
  return remember(prompt, accent) {
    buildAnnotatedString {
      var last = 0
      for (match in PromptPreset.PLACEHOLDER_PATTERN.findAll(prompt)) {
        append(prompt.substring(last, match.range.first))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(match.value) }
        last = match.range.last + 1
      }
      append(prompt.substring(last))
    }
  }
}
