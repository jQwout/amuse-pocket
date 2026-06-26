package com.aiopenpocket.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import com.aiopenpocket.voice.input.VoiceInputState

@Composable
fun MicButton(
  state: VoiceInputState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isListening = state is VoiceInputState.Listening
  val transition = rememberInfiniteTransition(label = "mic-pulse")
  val scale by transition.animateFloat(
    initialValue = 1f,
    targetValue = if (isListening) 1.2f else 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 700),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "mic-scale",
  )

  val (icon, description, tint) = when (state) {
    is VoiceInputState.Listening -> Triple(
      Icons.Filled.Mic,
      "Остановить запись",
      MaterialTheme.colorScheme.error,
    )
    is VoiceInputState.Error -> Triple(
      Icons.Filled.MicOff,
      "Ошибка распознавания",
      MaterialTheme.colorScheme.error,
    )
    VoiceInputState.Idle -> Triple(
      Icons.Filled.Mic,
      "Голосовой ввод",
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  IconButton(onClick = onClick, modifier = modifier) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = tint,
      modifier = if (isListening) Modifier.scale(scale) else Modifier,
    )
  }
}
