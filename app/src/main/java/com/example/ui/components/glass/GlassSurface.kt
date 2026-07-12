package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.DarkBg
import com.aiopenpocket.ui.theme.GlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * ЕДИНСТВЕННЫЙ файл, импортирующий Haze. Если API запиненной версии отличается —
 * правки только здесь (Task 1 build-спайк ловит это заранее).
 */

/**
 * Токен связи «источник ↔ стекло». Обёрнут, чтобы вызывающие не импортировали Haze.
 * @trace glass-design-system-21a3fe
 */
typealias GlassState = HazeState

@Composable
fun rememberGlassState(): GlassState = rememberHazeState()

/** Пометить контент источником, который размывается под стеклом. */
fun Modifier.glassSource(state: GlassState): Modifier = this.hazeSource(state)

/**
 * «Сильное» стекло с настоящим backdrop-blur (API 31+; на < 31 Haze даёт tint без
 * размытия). Для неподвижной обвязки: топбары, инпут-бары, нижняя навигация.
 */
@Composable
fun GlassSurface(
  state: GlassState,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  tint: Color = GlassTokens.LightTint,
  border: Brush? = GlassTokens.BorderBrush,
  content: @Composable BoxScope.() -> Unit,
) {
  val style = remember(tint) {
    HazeStyle(
      backgroundColor = DarkBg,
      tints = listOf(HazeTint(tint)),
      blurRadius = GlassTokens.BlurRadius,
    )
  }
  Box(
    modifier = modifier
      .clip(shape)
      .hazeEffect(state = state, style = style) {
        // Shape-aware blur masking, on top of the outer .clip(shape) above.
        blurredEdgeTreatment = BlurredEdgeTreatment(shape)
      }
      // Рим-блик по грани. Для скруглённых плавающих баров/навбара — приятная кромка; для
      // edge-to-edge поверхностей передаём border = null, иначе прямая линия режет во всю ширину.
      .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
    content = content,
  )
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}