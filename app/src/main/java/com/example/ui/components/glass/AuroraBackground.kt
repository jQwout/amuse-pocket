package com.aiopenpocket.ui.components.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aiopenpocket.ui.theme.DarkBg
import com.aiopenpocket.ui.theme.GlassTokens

/** Кисть + геометрия одного пятна ауроры, посчитанные для конкретного размера холста. */
private data class AuroraSpotBrush(val brush: Brush, val radius: Float, val center: Offset)

/**
 * Тёмная база + статичные радиальные пятна ауроры. Кисти пересчитываются в
 * [drawWithCache] только при смене размера холста; сама отрисовка — без
 * аллокаций на каждый кадр. Наносить на корневой контейнер экрана; поверх
 * кладётся контент (обычно он же — hazeSource через [glassSource]).
 * @trace glass-design-system-21a3fe
 */
fun Modifier.auroraBackground(baseColor: Color = DarkBg): Modifier =
  this.drawWithCache {
    val spots = GlassTokens.AuroraSpots.map { spot ->
      val center = Offset(size.width * spot.center.x, size.height * spot.center.y)
      val radius = maxOf(size.width, size.height) * spot.radiusFraction
      AuroraSpotBrush(
        brush = Brush.radialGradient(
          colors = listOf(spot.color, Color.Transparent),
          center = center,
          radius = radius,
        ),
        radius = radius,
        center = center,
      )
    }
    onDrawBehind {
      drawRect(baseColor)
      spots.forEach { spot ->
        // RadialGradient throws for radius<=0 (0×0 draw, or a future radiusFraction=0 spot).
        if (spot.radius > 0f) {
          drawCircle(brush = spot.brush, radius = spot.radius, center = spot.center)
        }
      }
    }
  }
