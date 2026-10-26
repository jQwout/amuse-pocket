package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * The "plate" chrome for floating bars and islands.
 *
 * ## There is NO backdrop blur here ANY MORE (and no Haze library)
 *
 * This used to be real glass: `hazeEffect` blurred the content under the bar (API 31+), and screens marked
 * their layers as sources through [glassSource]. The effect was removed by the product owner's decision — the
 * "shimmering" was not liked in use. Instead there is exactly what was asked for: a translucent plate in the
 * theme's background color. In essence it is the same branch that used to live here as a fallback for API<31
 * — now it is the only one.
 *
 * What this gives besides the look: the per-frame RenderEffect under the scrolling feed is gone (the main
 * suspect for the jank that made the chat switch blur off while scrolling), and so is the dependency on the
 * Android version — the look is the same on all APIs.
 *
 * ## Why [GlassState]/[glassSource] remain
 *
 * This was the only file that imported Haze (an invariant fixed in the design system), and that paid off:
 * removing the effect was an edit to one file. The "source ↔ plate" link token is kept as a dummy so ~10
 * calling screens did not have to be rewritten; when/if we decide blur will not come back, it can be cleaned
 * out in a separate mechanical pass.
 */

/**
 * The "source ↔ plate" link token. It used to be `HazeState`, now it is a dummy: the plate does not sample
 * the background, so it needs no source. Kept so that not all screens have to be rewritten at once.
 * @trace glass-design-system-21a3fe
 */
class GlassState internal constructor()

@Composable
fun rememberGlassState(): GlassState = remember { GlassState() }

/** It used to mark a layer as a blur source. Now a no-op — there is no blur. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassSource(state: GlassState): Modifier = this

/**
 * A plate for stationary chrome: top bars, input bars, the bottom navigation, floating islands.
 *
 * Layers from the bottom up: the theme's background color with alpha (it separates the bar from the content
 * but lets its tint through) → the [tint] film → an optional rim highlight along the edge. For edge-to-edge
 * surfaces pass `border = null`, otherwise a straight line cuts across the full width.
 */
@Composable
fun GlassSurface(
  state: GlassState,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  tint: Color = GlassTokens.LightTint,
  border: Brush? = GlassTokens.BorderBrush,
  /**
   * The backing's density. By default [PLATE_ALPHA] — the common look of the chrome. Raising it to 1f makes
   * sense where showing through hurts FUNCTIONALLY, not aesthetically: the input field is exactly such a case,
   * the feed's messages were readable through it.
   */
  plateAlpha: Float = PLATE_ALPHA,
  content: @Composable BoxScope.() -> Unit,
) {
  val backgroundColor = MaterialTheme.colorScheme.background
  Box(
    modifier = modifier
      .clip(shape)
      // The same color as the screen background, but with alpha: the plate reads as "ours" rather than someone
      // else's gray panel, while the text on it stays contrasting over the aurora.
      .background(backgroundColor.copy(alpha = plateAlpha))
      .background(tint)
      .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
    content = content,
  )
}

/**
 * The plate's density — a taste knob, tweak it here (one place for all bars and islands).
 *
 * 0.86 (the first attempt, as in the old fallback) turned out too transparent: card headings from under the
 * top bar were readable through it — the same defect that made "Chat"/"Reflection" merge with the feed in the
 * chat. The blur used to hide it: it blurred the backing rather than just letting it show through. Without
 * blur a higher density is needed — the plate still takes the background's tint, and the text on it stays
 * readable.
 */
private const val PLATE_ALPHA = 0.94f
