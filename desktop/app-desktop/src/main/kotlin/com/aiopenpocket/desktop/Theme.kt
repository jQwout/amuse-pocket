package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёмная тема по умолчанию: инструмент для разглядывания картинок и видео, светлый фон вокруг
 * результата мешает оценивать сам результат. Своих шрифтов не тянем — типографика :app живёт в
 * ассетах Android и переедет сюда вместе с `:core:ui` в фазе 4.
 */
private val DarkScheme = darkColorScheme(
  primary = Color(0xFF9FD0FF),
  onPrimary = Color(0xFF00344F),
  primaryContainer = Color(0xFF004B6F),
  onPrimaryContainer = Color(0xFFCDE5FF),
  secondary = Color(0xFFB9C8DA),
  background = Color(0xFF101418),
  onBackground = Color(0xFFE0E3E7),
  surface = Color(0xFF161B20),
  onSurface = Color(0xFFE0E3E7),
  surfaceVariant = Color(0xFF212A31),
  onSurfaceVariant = Color(0xFFBFC8D0),
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
  MaterialTheme(colorScheme = DarkScheme, content = content)
