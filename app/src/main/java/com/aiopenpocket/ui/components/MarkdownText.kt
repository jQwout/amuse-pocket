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
 * so it follows the app's theme out of the box.
 *
 * Local settings versus the library's defaults:
 *  - a synchronous parse that retains state (immediate + retainState) — the default async render showed an
 *    empty zero-height bubble until the parse finished (see the comment below);
 *  - [compact] — compact typography for chat bubbles (the default body = 16sp is too large).
 *  - tables render with line wrapping in cells (the default is `maxLines = 1` + an ellipsis, which cut long
 *    values like dates/deadlines off with an ellipsis).
 *  - [selectable] — wraps in a [SelectionContainer] so the text can be selected and copied (in chat
 *    bubbles). Off by default.
 */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  selectable: Boolean = false,
  /** A font size multiplier on top of the theme — the adjustable chat font size (see [com.aiopenpocket.agent.core.identity.ChatFontScale]). 1f = unchanged, the default for all non-chat calls. */
  fontScale: Float = 1f,
) {
  // The typography and the component set are remembered (remember): otherwise they would be allocated on every
  // recomposition of every bubble — noticeable jank when scrolling a long feed. The typography keys are the
  // theme and compact; the component set does not depend on the theme, so it is created once.
  // The chat font size scales the WHOLE theme once (rather than each markdown style separately): any style
  // derived from typo.* is automatically the right size, and a future style cannot be forgotten.
  // scaledBy(1f) returns the same theme — the default size with no allocations.
  val baseTypo = MaterialTheme.typography
  val typo = remember(baseTypo, fontScale) { baseTypo.scaledBy(fontScale) }

  // A fast path for plain text (that is most chat messages): without markdown markup we render a light Text.
  // The heavy Markdown AST render is not measured within one frame — because of that, during a fast scroll
  // the bubble first appeared empty and the text "arrived" a frame later, and the feed jumped. The color is
  // taken as in Markdown m3 (onBackground), so plain and markdown bubbles match.
  // The scan is cached by the text: without remember it would run on EVERY recomposition of the bubble
  // (search highlighting, the action panel, pinning, a window change) — O(n) over the whole string on every frame.
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

  // Text wrapping in table cells: the header/row blocks are overridden to allow multiple lines (the default
  // cuts a cell to one line and adds "…"). Narrow tables still scroll horizontally — that is MarkdownTable's
  // own behavior.
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
  // A synchronous parse (immediate) + retainState: without them the library parses in a coroutine on
  // Dispatchers.Default from an initial Loading state, and the loading slot by default is an empty Box of
  // ZERO height. The result was visible as "empty messages, then content": every markdown bubble was born
  // empty (and again on every return into the viewport, since the item's remember state dies), and the feed
  // jumped from the grown heights. immediate=true gives Success by the first frame; parsing a chat message
  // is the same category of work as laying out its text. retainState=true prevents falling back to Loading
  // when the content updates (a background re-parse swaps the content in place).
  // A bonus of rememberMarkdownState: its default parser/flavour/handler are remembered (unlike the defaults
  // of the Markdown(content=…) overload, which would be recreated on every recomposition, resetting the
  // content to Loading).
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
      // Text growth is not animated: the default animateContentSize turned every content growth into a visible
      // reflow of the feed.
      animations = markdownAnimations(animateTextSize = { this }),
      // A safety net for the Loading state (e.g. if immediate is ever turned off): raw text instead of an empty
      // box — the bubble never collapses to zero.
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
  // SelectionContainer handles a long press on the text itself (selection), so it takes it away from the
  // bubble's parent combinedClickable — pinning by long press stays on the paddings.
  if (selectable) {
    SelectionContainer { render() }
  } else {
    render()
  }
}

/** The regex of a numbered list at the start of a line: "1. ", "23. ". */
private val MARKDOWN_LIST_NUMBER = Regex("""^\d+\.\s""")

/**
 * A rough, CONSERVATIVE check for markdown markup. Any sign → true (the full render). The goal is to send most
 * plain text messages down the fast path (plain Text) without breaking those that really have markup. A false
 * positive (plain text with "*" or "_") is safe — just slightly more expensive; a false negative is
 * unacceptable, so the check is broad.
 */
private fun looksLikeMarkdown(text: String): Boolean {
  // Inline tokens: headings/emphasis/code/strikethrough/tables/quotes/links.
  for (c in text) {
    if (c == '#' || c == '*' || c == '_' || c == '`' || c == '~' || c == '|' || c == '>' ||
      c == '[' || c == ']'
    ) {
      return true
    }
  }
  // Bulleted/numbered lists at the start of a line: "- ", "+ ", "1. ".
  return text.lineSequence().any { line ->
    val t = line.trimStart()
    t.startsWith("- ") || t.startsWith("+ ") || MARKDOWN_LIST_NUMBER.containsMatchIn(t)
  }
}

/**
 * Builds [MarkdownTypography] from the active [Typography] by hand — without the @Composable factory
 * `markdownTypography`, so the result can be cached with `remember`. The values repeat the old call one to
 * one: the compact branch has compact sizes for chat bubbles (bodySmall body, headings pressed down to the
 * title tiers and below so the 57sp display sizes do not show up); the normal one has the defaults of library
 * 0.41.0 (hence the same fields the factory sets itself).
 */
private fun buildMarkdownTypography(typo: Typography, compact: Boolean): MarkdownTypography {
  // typo is ALREADY scaled by the caller (MarkdownText: typo.scaledBy(fontScale)) — here the markdown styles
  // are simply derived from it; there is no need to spread the scale over the fields.
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
 * Scales the WHOLE [Typography] once — each of its text styles through [scaledBy]. That way the adjustable
 * chat font size is applied at the theme boundary instead of being spread over 15 markdown fields; any style
 * derived from the result is automatically the right size. `scale == 1f` returns the same theme.
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
 * Multiplies a style's font size (and an absolute `sp` line height) by [scale]. `scale == 1f` returns the
 * original style unchanged (a fast path + no extra allocations for the default size).
 *
 * `lineHeight` is scaled ONLY when it is in `sp` (absolute): an `em` line height is already relative to the
 * font size and grows by itself — a second multiplication would inflate it; `Unspecified` is left alone.
 * `fontSize` in the theme is always `sp`, but the guard is symmetric in case of a non-standard style.
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
