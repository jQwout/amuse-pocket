package com.aiopenpocket.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.mikepenz.markdown.m3.Markdown

/**
 * Shared markdown renderer used by chat bubbles and lesson pages.
 *
 * Built on mikepenz/multiplatform-markdown-renderer (Material 3 flavor): a proper
 * Markdown AST renderer with solid support for headings, lists, tables, blockquotes,
 * code blocks and links. The `-m3` module pulls styling from the active MaterialTheme,
 * so it matches the forced-dark theme out of the box.
 */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
) {
  Markdown(
    content = text,
    modifier = modifier,
  )
}

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
    )
  }
}
