package com.aiopenpocket.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Shared markdown renderer used by chat bubbles and lesson pages.
 *
 * Built on mikepenz/multiplatform-markdown-renderer (Material 3 flavor): a proper
 * Markdown AST renderer with solid support for headings, lists, tables, blockquotes,
 * code blocks and links. The `-m3` module pulls styling from the active MaterialTheme,
 * so it matches the forced-dark theme out of the box.
 *
 * Локальные настройки против дефолтов библиотеки:
 *  - синхронный парс с сохранением состояния (immediate + retainState) — дефолтный асинхронный
 *    рендер показывал пустой пузырь нулевой высоты до завершения парса (см. комментарий ниже);
 *  - [compact] — компактная типографика для пузырей чата (дефолтный body = 16sp слишком крупный).
 *  - таблицы рендерим с переносом строк в ячейках (дефолт — `maxLines = 1` + ellipsis, из-за чего
 *    длинные значения вроде дат/сроков обрезались многоточием).
 *  - [selectable] — оборачивает в [SelectionContainer], чтобы текст можно было выделить и
 *    скопировать (в пузырях чата). По умолчанию выкл.
 */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  selectable: Boolean = false,
  /** Множитель кегля поверх темы — регулируемый размер шрифта чата (см. [com.aiopenpocket.agent.core.identity.ChatFontScale]). 1f = без изменений, дефолт для всех не-чатовых вызовов. */
  fontScale: Float = 1f,
) {
  // Типографику и набор компонентов запоминаем (remember): иначе они аллоцируются на каждую
  // рекомпозицию каждого пузыря — заметный джанк при скролле длинной ленты. Ключи типографики —
  // тема и compact; набор компонентов от темы не зависит, поэтому создаётся один раз.
  // Размер шрифта чата масштабируем на уровне ВСЕЙ темы один раз (а не каждый markdown-стиль по
  // отдельности): любой стиль, выведенный из typo.*, автоматически в нужном кегле, и будущий стиль
  // не забудешь отмасштабировать. scaledBy(1f) возвращает ту же тему — дефолтный размер без аллокаций.
  val baseTypo = MaterialTheme.typography
  val typo = remember(baseTypo, fontScale) { baseTypo.scaledBy(fontScale) }

  // Быстрый путь для обычного текста (а это большинство сообщений чата): без markdown-разметки
  // рендерим лёгким Text. Тяжёлый AST-рендер Markdown измеряется не за один кадр — из-за этого при
  // быстром скролле пузырь сперва появлялся пустым, а текст «доезжал» следующим кадром, и лента
  // прыгала. Цвет берём как у Markdown m3 (onBackground), чтобы обычные и markdown-пузыри совпадали.
  // Скан кэшируем по тексту: без remember он гонялся бы на КАЖДУЮ рекомпозицию пузыря (подсветка
  // поиска, панель действий, пин, смена окна) — O(n) по всей строке на каждый кадр.
  val isMarkdown = remember(text) { looksLikeMarkdown(text) }
  if (!isMarkdown) {
    val style = if (compact) typo.bodySmall else typo.bodyLarge
    val plain: @Composable () -> Unit = {
      Text(
        text = text,
        modifier = modifier,
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
      )
    }
    if (selectable) SelectionContainer { plain() } else plain()
    return
  }

  val typography = remember(typo, compact) { buildMarkdownTypography(typo, compact) }

  // Перенос текста в ячейках таблицы: переопределяем header/row-блоки, разрешая многострочность
  // (дефолт обрезает ячейку одной строкой и ставит «…»). Узкие таблицы по-прежнему скроллятся
  // горизонтально — это поведение самого MarkdownTable.
  val components = remember {
    markdownComponents(
      table = { model ->
        MarkdownTable(
          content = model.content,
          node = model.node,
          style = model.typography.table,
          headerBlock = { c, header, tableWidth, style ->
            MarkdownTableHeader(
              content = c,
              header = header,
              tableWidth = tableWidth,
              style = style,
              maxLines = Int.MAX_VALUE,
              overflow = TextOverflow.Clip,
            )
          },
          rowBlock = { c, header, tableWidth, style ->
            MarkdownTableRow(
              content = c,
              header = header,
              tableWidth = tableWidth,
              style = style,
              maxLines = Int.MAX_VALUE,
              overflow = TextOverflow.Clip,
            )
          },
        )
      },
    )
  }

  val dimens = markdownDimens(tableCellPadding = 10.dp)
  // Синхронный парс (immediate) + retainState: без них библиотека парсит в корутине на
  // Dispatchers.Default из начального состояния Loading, а loading-слот по умолчанию — пустой Box
  // НУЛЕВОЙ высоты. Итог был виден как «пустые сообщения, потом контент»: каждый markdown-пузырь
  // рождался пустым (и заново — при каждом возврате в вьюпорт, remember-состояние итема умирает),
  // а лента прыгала от доросших высот. immediate=true даёт Success уже к первому кадру; парс
  // сообщения чата — та же категория работы, что и layout его текста. retainState=true не даёт
  // откатиться в Loading при обновлении контента (фоновый ре-парс подменяет контент на месте).
  // Бонус rememberMarkdownState: его дефолтные parser/flavour/handler запомнены (в отличие от
  // дефолтов оверлоада Markdown(content=…), которые пересоздавались бы на каждую рекомпозицию,
  // сбрасывая контент в Loading).
  val markdownState = rememberMarkdownState(
    content = text,
    retainState = true,
    immediate = true,
  )
  val bodyStyle = if (compact) typo.bodySmall else typo.bodyLarge
  val render: @Composable () -> Unit = {
    Markdown(
      markdownState = markdownState,
      modifier = modifier,
      typography = typography,
      components = components,
      dimens = dimens,
      // Рост текста не анимируем: дефолтный animateContentSize превращал каждое дорастание
      // контента в видимый reflow ленты.
      animations = markdownAnimations(animateTextSize = { this }),
      // Страховка на случай Loading-состояния (например, если immediate когда-нибудь выключат):
      // сырой текст вместо пустого бокса — пузырь никогда не схлопывается в ноль.
      loading = { m ->
        Text(
          text = text,
          modifier = m,
          style = bodyStyle,
          color = MaterialTheme.colorScheme.onBackground,
        )
      },
    )
  }
  // SelectionContainer обрабатывает long-press на тексте сам (выделение), поэтому он перехватывает
  // его у родительского combinedClickable пузыря — закрепление по long-press остаётся на отступах.
  if (selectable) {
    SelectionContainer { render() }
  } else {
    render()
  }
}

/** Регулярка нумерованного списка в начале строки: «1. », «23. ». */
private val MARKDOWN_LIST_NUMBER = Regex("""^\d+\.\s""")

/**
 * Грубая, КОНСЕРВАТИВНАЯ проверка на наличие markdown-разметки. Любой признак → true (полноценный
 * рендер). Цель — отправить большинство обычных текстовых сообщений на быстрый путь (plain Text),
 * не сломав те, где разметка реально есть. Ложно-положительное (обычный текст со «*» или «_») безопасно
 * — просто чуть дороже; ложно-отрицательное недопустимо, поэтому проверяем широко.
 */
private fun looksLikeMarkdown(text: String): Boolean {
  // Инлайновые токены: заголовки/эмфазис/код/strikethrough/таблицы/цитаты/ссылки.
  for (c in text) {
    if (c == '#' || c == '*' || c == '_' || c == '`' || c == '~' || c == '|' || c == '>' ||
      c == '[' || c == ']'
    ) {
      return true
    }
  }
  // Маркированные/нумерованные списки в начале строки: «- », «+ », «1. ».
  return text.lineSequence().any { line ->
    val t = line.trimStart()
    t.startsWith("- ") || t.startsWith("+ ") || MARKDOWN_LIST_NUMBER.containsMatchIn(t)
  }
}

/**
 * Строит [MarkdownTypography] из активной [Typography] вручную — без @Composable-фабрики
 * `markdownTypography`, чтобы результат можно было закэшировать через `remember`. Значения один в
 * один повторяют прежний вызов: compact-ветка — компактные кегли для пузырей чата (тело bodySmall,
 * заголовки прижаты к title-тирам и ниже, чтобы не вылезали display-кегли 57sp); обычная — дефолты
 * библиотеки 0.41.0 (поэтому те же поля, что фабрика задаёт сама).
 */
private fun buildMarkdownTypography(typo: Typography, compact: Boolean): MarkdownTypography {
  // typo УЖЕ отмасштабирован вызывающей стороной (MarkdownText: typo.scaledBy(fontScale)) — здесь
  // просто выводим markdown-стили из него; масштаб размножать по полям не нужно.
  val code = typo.bodyMedium.copy(fontFamily = FontFamily.Monospace)
  val quote = typo.bodyMedium.plus(SpanStyle(fontStyle = FontStyle.Italic))
  val textLink = TextLinkStyles(
    style = typo.bodyLarge.copy(
      fontWeight = FontWeight.Bold,
      textDecoration = TextDecoration.Underline,
    ).toSpanStyle(),
  )
  return if (compact) {
    val body = typo.bodySmall
    DefaultMarkdownTypography(
      h1 = typo.titleMedium,
      h2 = typo.titleSmall,
      h3 = typo.bodyMedium.copy(fontWeight = FontWeight.Bold),
      h4 = typo.bodyMedium.copy(fontWeight = FontWeight.Bold),
      h5 = typo.bodySmall.copy(fontWeight = FontWeight.Bold),
      h6 = typo.bodySmall.copy(fontWeight = FontWeight.Bold),
      text = body,
      code = code,
      inlineCode = body.copy(fontFamily = FontFamily.Monospace),
      quote = quote,
      paragraph = body,
      ordered = body,
      bullet = body,
      list = body,
      textLink = textLink,
      table = typo.labelSmall,
    )
  } else {
    val body = typo.bodyLarge
    DefaultMarkdownTypography(
      h1 = typo.displayLarge,
      h2 = typo.displayMedium,
      h3 = typo.displaySmall,
      h4 = typo.headlineMedium,
      h5 = typo.headlineSmall,
      h6 = typo.titleLarge,
      text = body,
      code = code,
      inlineCode = body.copy(fontFamily = FontFamily.Monospace),
      quote = quote,
      paragraph = body,
      ordered = body,
      bullet = body,
      list = body,
      textLink = textLink,
      table = body,
    )
  }
}

/**
 * Масштабирует ВСЮ [Typography] один раз — каждый её текст-стиль через [scaledBy]. Так регулируемый
 * размер шрифта чата применяется на границе темы, а не размножается по 15 markdown-полям; любой
 * стиль, выведенный из результата, автоматически в нужном кегле. `scale == 1f` возвращает ту же тему.
 */
internal fun Typography.scaledBy(scale: Float): Typography =
  if (scale == 1f) this
  else copy(
    displayLarge = displayLarge.scaledBy(scale),
    displayMedium = displayMedium.scaledBy(scale),
    displaySmall = displaySmall.scaledBy(scale),
    headlineLarge = headlineLarge.scaledBy(scale),
    headlineMedium = headlineMedium.scaledBy(scale),
    headlineSmall = headlineSmall.scaledBy(scale),
    titleLarge = titleLarge.scaledBy(scale),
    titleMedium = titleMedium.scaledBy(scale),
    titleSmall = titleSmall.scaledBy(scale),
    bodyLarge = bodyLarge.scaledBy(scale),
    bodyMedium = bodyMedium.scaledBy(scale),
    bodySmall = bodySmall.scaledBy(scale),
    labelLarge = labelLarge.scaledBy(scale),
    labelMedium = labelMedium.scaledBy(scale),
    labelSmall = labelSmall.scaledBy(scale),
  )

/**
 * Умножает кегль (и абсолютный `sp`-межстрочный интервал) стиля на [scale]. `scale == 1f` возвращает
 * исходный стиль без изменений (быстрый путь + отсутствие лишних аллокаций для дефолтного размера).
 *
 * `lineHeight` масштабируем ТОЛЬКО когда он задан в `sp` (абсолют): `em`-интервал уже относителен
 * кеглю и вырастет сам — второе умножение раздуло бы его; `Unspecified` не трогаем. `fontSize`
 * в теме всегда `sp`, но guard симметричен на случай нестандартного стиля.
 */
internal fun TextStyle.scaledBy(scale: Float): TextStyle =
  if (scale == 1f) this
  else copy(
    fontSize = if (fontSize.type == TextUnitType.Sp) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.type == TextUnitType.Sp) lineHeight * scale else lineHeight,
  )

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun MarkdownTextPreview() {
  MyApplicationTheme {
    MarkdownText(
      text = """
        ## Заголовок

        Обычный абзац с **жирным** и *курсивом*.

        - первый пункт
        - второй пункт

        > Цитата
      """.trimIndent(),
      modifier = Modifier.padding(16.dp),
      compact = true,
    )
  }
}
