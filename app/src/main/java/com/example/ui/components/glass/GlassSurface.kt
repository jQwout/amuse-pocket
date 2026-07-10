package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
  tint: Color = GlassTokens.StrongTint,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier = modifier
      .clip(shape)
      .hazeEffect(
        state = state,
        style = HazeStyle(
          backgroundColor = DarkBg,
          tints = listOf(HazeTint(tint)),
          blurRadius = GlassTokens.BlurRadius,
        ),
      )
      .border(1.dp, GlassTokens.borderBrush(), shape),
    content = content,
  )
}
