package com.aiopenpocket.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
