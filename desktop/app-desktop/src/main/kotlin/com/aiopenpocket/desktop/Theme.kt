package com.aiopenpocket.desktop

// @trace desktop-genai-client-4c63b6

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Тёмная тема: инструмент для разглядывания картинок и видео, светлый фон вокруг результата мешает
 * оценивать сам результат.
 *
 * Акцент фиолетовый и **холодно-синие поверхности**: по макету. Прежняя голубая схема Material
 * растворяла кнопку запуска в фоне — на тёмно-синем голубой primary читается как «неактивно».
 *
 * Своих шрифтов не тянем: типографика :app живёт в ассетах Android и переедет сюда вместе с
 * `:core:ui`, если фаза 4 когда-нибудь дойдёт до общего UI-модуля.
 */
private val DarkScheme = darkColorScheme(
  primary = Color(0xFF7C6CF0),
  onPrimary = Color(0xFFFFFFFF),
  // Контейнер заметно темнее primary: им залит выбранный пункт рельсы и активные чипы, и на нём
  // должен читаться светлый текст, а не спорить с ним яркостью.
  primaryContainer = Color(0xFF2A2560),
  onPrimaryContainer = Color(0xFFCFC7FF),
  secondary = Color(0xFFB4B0D6),
  background = Color(0xFF0F1117),
  onBackground = Color(0xFFE6E7EE),
  surface = Color(0xFF161922),
  onSurface = Color(0xFFE6E7EE),
  // Полосатые заглушки результатов и подложки карточек файлов.
  surfaceVariant = Color(0xFF1E2230),
  onSurfaceVariant = Color(0xFF9AA0B4),
  outline = Color(0xFF2E3345),
  error = Color(0xFFFF8A80),
  onError = Color(0xFF3A0906),
)

/** Скругления крупнее дефолтных Material: по макету карточки заметно круглее. */
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(14.dp),
  extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
  MaterialTheme(colorScheme = DarkScheme, shapes = AppShapes, content = content)
