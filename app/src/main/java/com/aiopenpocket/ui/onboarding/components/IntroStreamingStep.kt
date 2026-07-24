package com.aiopenpocket.ui.onboarding.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.StreamingBubble
import com.aiopenpocket.ui.theme.MyApplicationTheme

@Composable
fun IntroStreamingStep(
  streamingText: String,
  isStreaming: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 8.dp, vertical = 16.dp),
  ) {
    Text(
      text = "Знакомство",
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Агент",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
    StreamingBubble(
      text = streamingText,
      showCursor = isStreaming,
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun IntroStreamingStepPreview() {
  MyApplicationTheme {
    IntroStreamingStep(
      streamingText = "Привет! Я твой ассистент. Помогу с заметками, напоминаниями и…",
      isStreaming = true,
    )
  }
}
