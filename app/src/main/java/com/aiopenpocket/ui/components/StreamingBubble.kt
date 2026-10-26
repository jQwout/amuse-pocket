package com.aiopenpocket.ui.components

// @trace streaming-llm-chat-6595a1

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.theme.MyApplicationTheme

@Composable
fun StreamingBubble(
  text: String,
  modifier: Modifier = Modifier,
  showCursor: Boolean = false,
  isWaiting: Boolean = false,
  /** The same multiplier as [MarkdownText] in the bubble: swapping the stream for the finished message must not change the text size. */
  fontScale: Float = 1f,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(
      modifier = Modifier
        .padding(12.dp),
    ) {
      when {
        text.isNotEmpty() -> {
          val displayText = if (showCursor) "$text▌" else text
          // The font scale is remembered by (theme, fontScale): the stream recomposes on every token, and without
          // remember scaledBy would allocate a TextStyle on every delta at a non-default size.
          val baseStyle = MaterialTheme.typography.bodySmall
          val style = remember(baseStyle, fontScale) { baseStyle.scaledBy(fontScale) }
          Text(
            text = displayText,
            style = style,
            // The same color and size as the saved message (MarkdownText's plain path / markdown m3 — onBackground):
            // swapping the bubble for the message must not change how the text looks.
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
        isWaiting -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
            )
            Text(
              text = tr("Думаю…", "Thinking…"),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        }
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun StreamingBubbleTypingPreview() {
  MyApplicationTheme {
    StreamingBubble(text = "Сейчас разберу твою задачу и распиш", showCursor = true)
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun StreamingBubbleWaitingPreview() {
  MyApplicationTheme {
    StreamingBubble(text = "", isWaiting = true)
  }
}
