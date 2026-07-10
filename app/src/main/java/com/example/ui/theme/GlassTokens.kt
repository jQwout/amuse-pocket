package com.aiopenpocket.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Токены глассморфизма. Тема всегда тёмная ([MyApplicationTheme]) — значения
 * статичны, CompositionLocal не нужен.
 */
object GlassTokens {

  /** Тонировка «сильного» стекла (bars/навигация) поверх тёмной подложки. */
  val StrongTint: Color = Color.White.copy(alpha = 0.10f)

  /** Тонировка «слабого» стекла (карточки в скролле, без blur). */
  val LightTint: Color = Color.White.copy(alpha = 0.06f)

  /** Радиус backdrop-blur сильного стекла. */
  val BlurRadius: Dp = 20.dp

  /** Блик на грани: светлее сверху, гаснет вниз. */
  fun borderBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.25f),
      Color.White.copy(alpha = 0.05f),
    ),
  )

  /** Пятно ауроры; center/radiusFraction — доли размера холста (0..1). */
  data class AuroraSpot(val color: Color, val center: Offset, val radiusFraction: Float)

  /** 3 статичных пятна: фиолет сверху-слева, тёмный фиолет справа, глубокий синий снизу. */
  val AuroraSpots: List<AuroraSpot> = listOf(
    AuroraSpot(PurpleAccent.copy(alpha = 0.22f), Offset(0.15f, 0.10f), 0.60f),
    AuroraSpot(PurpleAccentDark.copy(alpha = 0.45f), Offset(0.90f, 0.25f), 0.70f),
    AuroraSpot(Color(0xFF1B2A4A).copy(alpha = 0.55f), Offset(0.50f, 0.95f), 0.90f),
  )
}
