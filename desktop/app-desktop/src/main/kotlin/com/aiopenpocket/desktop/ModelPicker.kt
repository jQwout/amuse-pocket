package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aiopenpocket.genai.ModelCard
import com.aiopenpocket.genai.ModelPreviews
import com.aiopenpocket.genai.PickerContext
import com.aiopenpocket.genai.PickerSort
import com.aiopenpocket.genai.capabilitySentence
import com.aiopenpocket.genai.capabilityTags
import com.aiopenpocket.genai.contextSummary
import com.aiopenpocket.genai.formatUsd
import com.aiopenpocket.genai.humanModelName
import com.aiopenpocket.genai.matches
import com.aiopenpocket.genai.modelVendor
import com.aiopenpocket.genai.pluralRu
import com.aiopenpocket.genai.shortLabel
import com.aiopenpocket.genai.fullLabel
import com.aiopenpocket.genai.sortCards

/**
 * Строка выбранной модели в форме — она же вход в окно выбора.
 *
 * Пришла на смену текстовому полю со слагом. Поле показывало ровно одну вещь — что уйдёт в запрос, —
 * и требовало от пользователя знать слаг наизусть; всё остальное (имя, цена, чем модель отличается
 * от соседней) приходилось искать в отдельном диалоге. Здесь то же самое место в форме показывает
 * имя, слаг и цену разом, а клик открывает окно.
 *
 * [card] может быть null: в форме мог остаться слаг, которого нет в живом каталоге — из истории или
 * из своего `genai-models.json`. Такой слаг не стирается и не подменяется: показываем как есть.
 */
@Composable
fun ModelChoiceRow(
  modelId: String,
  card: ModelCard?,
  /** Выбранный тир: у моделей с ценой по тирам от него зависит число. */
  tier: String?,
  previews: ModelPreviews?,
  onOpen: () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
  ) {
    Row(
      Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PreviewBox(modelId, previews, width = 56.dp, height = 40.dp)
      Column(Modifier.weight(1f)) {
        Text(
          // Пустой слаг бывает ровно один момент — до того, как приедет каталог и подставит модель
          // по умолчанию. Гнать заглушку через humanModelName нельзя: выходит «Модель Не Выбрана».
          card?.entry?.displayName
            ?: modelId.takeIf { it.isNotBlank() }?.let(::humanModelName)
            ?: "Модель не выбрана",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          // Слаг остаётся на виду: именно он уходит в запрос, и подменять его красивым именем нельзя.
          modelId.ifBlank { "выбери модель" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      card?.let {
        Text(
          it.price.shortLabel(tier),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        "Сменить",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

/**
 * Окно выбора модели.
 *
 * Диалог, а не выпадающее меню, — по той же причине, что и прежде: меню привязано к полю в нижней
 * трети формы, разворачивалось вверх поверх промпта и уносило за верхний край строку поиска. Новое
 * здесь другое — две панели вместо одной колонки: список отвечает на «какие есть», панель справа на
 * «что это за модель», и раньше второй вопрос было некуда задать.
 */
@Composable
fun ModelPickerDialog(
  cards: List<ModelCard>,
  current: String,
  context: PickerContext,
  previews: ModelPreviews?,
  onDismiss: () -> Unit,
  onPick: (String) -> Unit,
  onToggleFavorite: (String) -> Unit,
) {
  // usePlatformDefaultWidth = false — не украшение, а условие существования этой раскладки.
  //
  // По умолчанию Dialog зажимает содержимое платформенной шириной диалога (около 640 px), и
  // `Modifier.width(1120.dp)` внутри просто не действует: панель ужимается, колонка списка
  // получает пару сотен пикселей, а текст в ней сыплется по одной букве в строку. Прежний список
  // на 520 dp в этот потолок не упирался, поэтому проблема и вылезла только сейчас.
  //
  // Offscreen-снимок (`shots`) этого не ловит по построению: он рисует ModelPickerPanel напрямую,
  // без Dialog, то есть без его ограничений. Проверять раскладку окна можно только живым запуском.
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    ModelPickerPanel(
      cards = cards,
      current = current,
      context = context,
      previews = previews,
      onDismiss = onDismiss,
      onPick = onPick,
      onToggleFavorite = onToggleFavorite,
    )
  }
}

/**
 * Тело окна отдельно от самого окна — ради offscreen-снимка: диалог на десктопе живёт своей сценой
 * и в кадр главного экрана не попадает вовсе. Тот же приём, что и у прежнего списка моделей.
 *
 * Все данные приходят готовыми ([ModelCard] собирается в ядре): панель ничего не считает сама, и
 * поэтому её можно снять с любым набором строк, не поднимая ни сети, ни истории.
 */
@Composable
fun ModelPickerPanel(
  cards: List<ModelCard>,
  current: String,
  context: PickerContext = PickerContext(),
  previews: ModelPreviews? = null,
  onDismiss: () -> Unit = {},
  onPick: (String) -> Unit = {},
  onToggleFavorite: (String) -> Unit = {},
) {
  var query by remember { mutableStateOf("") }
  var sort by remember { mutableStateOf(PickerSort.RECOMMENDED) }
  var favoritesOnly by remember { mutableStateOf(false) }
  var useContext by remember { mutableStateOf(true) }
  // Подсветка в списке живёт отдельно от выбора в форме: клик по строке показывает модель в панели
  // справа, а форму меняет только «Выбрать». Иначе посмотреть на соседнюю модель было бы нельзя,
  // не поменяв заодно то, что уйдёт в запрос.
  var highlighted by remember { mutableStateOf(current) }

  val visible = remember(cards, query, favoritesOnly, sort, context) {
    sortCards(
      cards.filter { it.matches(query) && (!favoritesOnly || it.favorite) },
      sort,
      context.resolution,
    )
  }
  val grouping = useContext && !context.isEmpty
  val fitting = if (grouping) visible.filter { it.fits } else visible
  val rest = if (grouping) visible.filterNot { it.fits } else emptyList()
  val shown = cards.firstOrNull { it.id == highlighted } ?: visible.firstOrNull()

  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier
      // Доля окна с потолком, а не жёсткие 1120×720: окно приложения можно уменьшить, и панель
      // фиксированного размера тогда вылезла бы за его края вместе с кнопкой «Выбрать».
      .fillMaxWidth(0.94f)
      .fillMaxHeight(0.92f)
      .widthIn(max = 1120.dp)
      .heightIn(max = 760.dp)
      // Esc ловим на корне, а не на поле поиска: превью-обход идёт сверху вниз, поэтому окно
      // закроется и когда курсор стоит в поиске.
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
      PickerHeader(
        query = query,
        onQuery = { query = it },
        total = cards.size,
        vendors = cards.mapTo(mutableSetOf()) { modelVendor(it.id) }.count { it.isNotBlank() },
        onDismiss = onDismiss,
      )
      PickerSortRow(
        sort = sort,
        onSort = { sort = it },
        favoritesOnly = favoritesOnly,
        onFavoritesOnly = { favoritesOnly = it },
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)

      Row(Modifier.weight(1f)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
          if (!context.isEmpty) {
            ContextBanner(context, useContext) { useContext = it }
          }
          if (visible.isEmpty()) {
            EmptyList(query = query, onPick = onPick, favoritesOnly = favoritesOnly)
          } else {
            LazyColumn(Modifier.fillMaxSize()) {
              if (rest.isEmpty()) {
                items(fitting, key = { it.id }) { card ->
                  ModelRow(card, current, highlighted, context.resolution, previews, { highlighted = it }, onPick, onToggleFavorite)
                }
              } else {
                item { GroupTitle("ПОД ВАШ ЗАПРОС", "по файлам, формату и тиру") }
                items(fitting, key = { it.id }) { card ->
                  ModelRow(card, current, highlighted, context.resolution, previews, { highlighted = it }, onPick, onToggleFavorite)
                }
                item {
                  GroupTitle("ОСТАЛЬНЫЕ", "${rest.size} ${pluralRu(rest.size, "модель", "модели", "моделей")}")
                }
                items(rest, key = { it.id }) { card ->
                  ModelRow(card, current, highlighted, context.resolution, previews, { highlighted = it }, onPick, onToggleFavorite)
                }
              }
            }
          }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        ModelDetails(
          card = shown,
          tier = context.resolution,
          previews = previews,
          onPick = onPick,
          onToggleFavorite = onToggleFavorite,
        )
      }
    }
  }
}

@Composable
private fun PickerHeader(
  query: String,
  onQuery: (String) -> Unit,
  total: Int,
  vendors: Int,
  onDismiss: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Выбрать модель", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
      value = query,
      onValueChange = onQuery,
      placeholder = { Text("Название, вендор или слаг") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp)) },
      singleLine = true,
      shape = ButtonShape,
      // Высоту не занижаем: у OutlinedTextField она 56 dp вместе с местом под текст, и «сжать до
      // 48» означает обрезать подсказку по горизонтали — проверено на снимке.
      modifier = Modifier.weight(1f),
    )
    Text(
      "$total ${pluralRu(total, "модель", "модели", "моделей")} · " +
        "$vendors ${pluralRu(vendors, "вендор", "вендора", "вендоров")}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    IconButton(onClick = onDismiss) {
      Icon(Icons.Default.Close, contentDescription = "Закрыть", Modifier.size(18.dp))
    }
  }
}

@Composable
private fun PickerSortRow(
  sort: PickerSort,
  onSort: (PickerSort) -> Unit,
  favoritesOnly: Boolean,
  onFavoritesOnly: (Boolean) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "СОРТИРОВКА",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // «Быстрее» и «Качество» из макета здесь нет намеренно: ни скорости, ни оценки качества API не
    // отдаёт ни в каком виде, а рисовать пять точек по своему усмотрению — это выдать редакторскую
    // догадку за измерение.
    for (option in PickerSort.entries) {
      FilterChip(
        selected = sort == option,
        onClick = { onSort(option) },
        label = { Text(option.label) },
      )
    }
    Spacer(Modifier.weight(1f))
    FilterChip(
      selected = favoritesOnly,
      onClick = { onFavoritesOnly(!favoritesOnly) },
      label = { Text("Только избранные") },
      leadingIcon = {
        Icon(
          if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
          contentDescription = null,
          Modifier.size(16.dp),
        )
      },
    )
  }
}

/**
 * Что из формы учитывается при отборе. Кнопка «Не учитывать» не прячет и не возвращает модели — она
 * только схлопывает две группы в один список: спрятать подходящую модель из-за незаявленного
 * параметра было бы куда хуже, чем показать её ниже.
 */
@Composable
private fun ContextBanner(context: PickerContext, enabled: Boolean, onToggle: (Boolean) -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    Row(
      Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        if (enabled) {
          "Учитываем текущий запрос: ${contextSummary(context)}. Что не подходит — ниже."
        } else {
          "Текущий запрос не учитываем: ${contextSummary(context)}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Text(
        if (enabled) "Не учитывать" else "Учитывать",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { onToggle(!enabled) },
      )
    }
  }
}

@Composable
private fun GroupTitle(title: String, hint: String) {
  Row(
    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
  }
}

/**
 * Строка списка. Клик подсвечивает и показывает модель справа, двойной клик выбирает сразу: ходить
 * за кнопкой «Выбрать» ради модели, которую и так знаешь, незачем.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(
  card: ModelCard,
  current: String,
  highlighted: String,
  tier: String?,
  previews: ModelPreviews?,
  onHighlight: (String) -> Unit,
  onPick: (String) -> Unit,
  onToggleFavorite: (String) -> Unit,
) {
  val chosen = card.id == current
  val active = card.id == highlighted
  Row(
    Modifier
      .fillMaxWidth()
      .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
      .combinedClickable(
        onClick = { onHighlight(card.id) },
        onDoubleClick = { onPick(card.id) },
      )
      .padding(horizontal = 20.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    PreviewBox(card.id, previews, width = 64.dp, height = 44.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chosen) {
          Icon(
            Icons.Default.Check,
            contentDescription = "выбрана сейчас",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
          )
        }
        Text(
          card.entry.displayName,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
          card.id,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (card.entry.description.isNotBlank()) {
        // Описание от вендора и по-английски: перевести его нечем, а выбросить нельзя — это
        // единственное, чем один `gemini-*-image` отличается от соседнего на вид.
        Text(
          card.entry.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        card.misfit?.let { TagPill(it, warning = true) }
        capabilityTags(card.entry).forEach { TagPill(it) }
        if (card.runs > 0) {
          Text(
            "· вы запускали ${card.runs} ${pluralRu(card.runs, "раз", "раза", "раз")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        card.price.shortLabel(tier),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "Выбрать",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable { onPick(card.id) },
        )
        Icon(
          if (card.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
          contentDescription = "в избранное",
          tint = if (card.favorite) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier.size(16.dp).clickable { onToggleFavorite(card.id) },
        )
      }
    }
  }
}

/**
 * Место под пример картинки. Без витрины (offscreen-снимок, тесты) — полосатая заглушка того же
 * размера, а не пустота: иначе на снимке раскладка окна выглядит не такой, какой её увидит
 * пользователь, и проверять по нему нечего.
 */
@Composable
private fun PreviewBox(modelId: String, previews: ModelPreviews?, width: Dp, height: Dp) {
  if (previews != null) {
    ModelPreviewThumb(modelId, previews, width = width, height = height)
  } else {
    Box(
      Modifier.size(width, height)
        .clip(MaterialTheme.shapes.extraSmall)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      StripePlaceholder(width = width, height = height)
    }
  }
}

@Composable
private fun TagPill(text: String, warning: Boolean = false) {
  Surface(
    shape = MaterialTheme.shapes.extraSmall,
    color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelSmall,
      color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

/**
 * Панель деталей: то, ради чего окно стало двухпанельным.
 *
 * Здесь нет блока «почему предлагаем» из макета — обещать «держит лицо по референсу» неоткуда.
 * Вместо него [capabilitySentence] пересказывает то, что модель сама о себе заявила.
 */
@Composable
private fun ModelDetails(
  card: ModelCard?,
  tier: String?,
  previews: ModelPreviews?,
  onPick: (String) -> Unit,
  onToggleFavorite: (String) -> Unit,
) {
  Column(Modifier.width(380.dp).fillMaxHeight()) {
    if (card == null) {
      Text(
        "Выбери строку слева",
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
      card.entry.vendor.takeIf { it.isNotBlank() }?.let {
        Text(
          it.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(card.entry.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Text(card.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

      // 170, а не 200: на снимке при 200 «ЦЕНА» и «ВАША ИСТОРИЯ» уезжали под нижний край панели, а
      // это ровно то, ради чего в неё смотрят. Пример картинки важен, но не важнее цены.
      PreviewBox(card.id, previews, width = 340.dp, height = 170.dp)

      if (card.entry.description.isNotBlank()) {
        Text(card.entry.description, style = MaterialTheme.typography.bodySmall)
      }

      DetailBlock("ЧТО УМЕЕТ", capabilitySentence(card.entry))
      DetailBlock("ЦЕНА", card.price.fullLabel())
      DetailBlock(
        "ВАША ИСТОРИЯ",
        when {
          card.runs == 0 -> "эту модель ещё не запускали"
          card.averageUsd == null ->
            "${card.runs} ${pluralRu(card.runs, "запуск", "запуска", "запусков")}, цену ни один не сообщил"
          else ->
            "${card.runs} ${pluralRu(card.runs, "запуск", "запуска", "запусков")}, " +
              "в среднем ${formatUsd(card.averageUsd)}"
        },
      )
      card.misfit?.let {
        DetailBlock("ПОД ТЕКУЩИЙ ЗАПРОС", "не подходит: $it. Выбрать её всё равно можно.")
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Row(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(onClick = { onPick(card.id) }, shape = ButtonShape) { Text("Выбрать") }
      OutlinedButton(onClick = { onToggleFavorite(card.id) }, shape = ButtonShape) {
        Icon(
          if (card.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
          contentDescription = null,
          Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (card.favorite) "В избранном" else "В избранное")
      }
      Spacer(Modifier.weight(1f))
      Text("Esc", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
  }
}

@Composable
private fun DetailBlock(title: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(body, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Пусто — и это разные «пусто»: каталог не приехал, фильтр по избранному ничего не оставил, поиск
 * не нашёл. Последний случай — единственное место, где остался ручной ввод слага: каталог живой, но
 * OpenRouter выкатывает модели раньше, чем они попадают в списки.
 */
@Composable
private fun EmptyList(query: String, favoritesOnly: Boolean, onPick: (String) -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      when {
        query.isNotBlank() -> "Ничего не нашлось по «$query»."
        favoritesOnly -> "В избранном пока пусто — отметь модель сердцем."
        else -> "Каталог пуст: нет сети и нет кеша."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (query.contains('/')) {
      OutlinedButton(onClick = { onPick(query.trim()) }, shape = ButtonShape) {
        Text("Использовать «${query.trim()}» как слаг")
      }
    } else if (query.isNotBlank()) {
      Text(
        "Свою модель можно вписать прямо сюда — слаг выглядит как vendor/model.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}
