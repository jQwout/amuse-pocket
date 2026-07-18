package com.aiopenpocket.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Токены глассморфизма. Тинты и блик-бордер зависят от темы ([LocalThemeIsDark]):
 * на тёмной — светлая плёнка поверх чернил, на светлой — лёгкое затемнение поверх
 * бумаги. Структурные значения (радиус blur, форма, геометрия ауроры) от темы не
 * зависят и остаются статичными.
 * @trace glass-design-system-21a3fe
 */
object GlassTokens {

  // Сырые тинты по темам — именованные константы, чтобы инвариант «сильное стекло плотнее
  // слабого» проверялся юнит-тестом без композиции (геттеры ниже лишь выбирают по теме).
  internal val StrongTintDark = Color.White.copy(alpha = 0.10f)
  internal val StrongTintLight = Color.Black.copy(alpha = 0.06f)
  internal val LightTintDark = Color.White.copy(alpha = 0.06f)
  internal val LightTintLight = Color.Black.copy(alpha = 0.04f)

  /** Тонировка «сильного» стекла (bars/навигация). Светлая плёнка на тёмной, затемнение на светлой. */
  val StrongTint: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalThemeIsDark.current) StrongTintDark else StrongTintLight

  /** Тонировка «слабого» стекла (карточки в скролле, без blur). */
  val LightTint: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalThemeIsDark.current) LightTintDark else LightTintLight

  /** Радиус backdrop-blur сильного стекла. */
  val BlurRadius: Dp = 20.dp

  /** Форма карточки по умолчанию. */
  val CardShape: Shape = RoundedCornerShape(20.dp)

  /** Блик на грани: светлее сверху, гаснет вниз (на светлой — тёмная волосяная обводка). */
  val BorderBrush: Brush
    @Composable @ReadOnlyComposable
    get() =
      if (LocalThemeIsDark.current) {
        Brush.verticalGradient(
          colors = listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.05f)),
        )
      } else {
        Brush.verticalGradient(
          colors = listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.03f)),
        )
      }

  /** Пятно ауроры; center/radiusFraction — доли размера холста (0..1). */
  data class AuroraSpot(val color: Color, val center: Offset, val radiusFraction: Float)

  /**
   * 3 статичных пятна тёмной ауроры: мягкий лайм сверху-слева, глубокая оливка справа,
   * тёмно-зелёная глубина снизу. На светлой теме аврора гасится в [Modifier.auroraBackground]
   * (пустой список), поэтому пятна заданы только для тёмной подложки.
   */
  val AuroraSpots: List<AuroraSpot> = listOf(
    // Верхне-левое светлое пятно приглушено (0.14): на большей альфе near-white текст заголовков/
    // описаний в верхней части экранов терял контраст. Аврора остаётся, просто мягче.
    AuroraSpot(LimeSignal.copy(alpha = 0.14f), Offset(0.15f, 0.10f), 0.60f),
    AuroraSpot(OliveAccent.copy(alpha = 0.42f), Offset(0.90f, 0.25f), 0.70f),
    AuroraSpot(Color(0xFF10261B).copy(alpha = 0.55f), Offset(0.50f, 0.95f), 0.90f),
  )
}
