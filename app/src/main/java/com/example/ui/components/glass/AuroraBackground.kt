package com.aiopenpocket.ui.components.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aiopenpocket.ui.theme.DarkBg
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * Тёмная база + статичные радиальные пятна ауроры. Рисуется в [drawBehind] —
 * ноль рекомпозиций. Наносить на корневой контейнер экрана; поверх кладётся
 * контент (обычно он же — hazeSource через [glassSource]).
 */
fun Modifier.auroraBackground(baseColor: Color = DarkBg): Modifier =
  this.drawBehind {
    drawRect(baseColor)
    GlassTokens.AuroraSpots.forEach { spot ->
      val center = Offset(size.width * spot.center.x, size.height * spot.center.y)
      val radius = maxOf(size.width, size.height) * spot.radiusFraction
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(spot.color, Color.Transparent),
          center = center,
          radius = radius,
        ),
        radius = radius,
        center = center,
      )
    }
  }
