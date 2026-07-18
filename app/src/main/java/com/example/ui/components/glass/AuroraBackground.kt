package com.aiopenpocket.ui.components.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.LocalThemeIsDark

/** Кисть + геометрия одного пятна ауроры, посчитанные для конкретного размера холста. */
private data class AuroraSpotBrush(val brush: Brush, val radius: Float, val center: Offset)

/**
 * База + статичные радиальные пятна ауроры. На тёмной теме — чернильная база с
 * лайм/оливковыми пятнами; на светлой аврора отключается (пустой список пятен),
 * остаётся чистая бумажная база — намеренный минимализм, а не тёмный эффект на светлом
 * фоне. Кисти пересчитываются в [drawWithCache] только при смене размера холста; сама
 * отрисовка — без аллокаций на кадр. Наносить на корневой контейнер экрана.
 * @trace glass-design-system-21a3fe
 */
@Composable
fun Modifier.auroraBackground(
  baseColor: Color = MaterialTheme.colorScheme.background,
  spots: List<GlassTokens.AuroraSpot> =
    if (LocalThemeIsDark.current) GlassTokens.AuroraSpots else emptyList(),
): Modifier =
  this.drawWithCache {
    val spotBrushes = spots.map { spot ->
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
      spotBrushes.forEach { spot ->
        // RadialGradient throws for radius<=0 (0×0 draw, or a future radiusFraction=0 spot).
        if (spot.radius > 0f) {
          drawCircle(brush = spot.brush, radius = spot.radius, center = spot.center)
        }
      }
    }
  }
