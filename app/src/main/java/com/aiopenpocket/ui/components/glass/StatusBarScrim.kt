package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * The glass band the clock, the battery and the network sit on: the theme's background at the same density
 * as the floating bars ([GlassTokens.PlateAlpha]) for the height of the system status bar, then a short fade
 * into the content below it.
 *
 * Why. The app draws edge-to-edge, so the feed slides right under the system icons. Even with the icon color
 * chosen right (fixed by [com.aiopenpocket.ui.theme.MyApplicationTheme], which syncs
 * `isAppearanceLightStatusBars` with the APP's theme rather than the system's), on a busy spot — a light card,
 * an image, a bright aurora spot — they still get lost.
 *
 * Why it is no longer a fade that starts at 70%. That is what this was, and on the device it was not enough:
 * a 70%-dense top meant 30% of a lesson card's bright silver still came through at the clock, and the fade
 * reached full transparency by the bottom edge of the status bar — exactly where the first line of a message
 * lies. Message text read THROUGH the battery icon. The design intent is that content passes UNDER glass, so
 * the band the system icons stand on is now actual glass, and the dissolve happens below them, over
 * [FADE_TAIL], where there is nothing to protect.
 *
 * It is still not a "status bar" in the old sense: it is the screen's own background color, not a panel, it
 * carries no border or shadow, and the content keeps scrolling under it.
 *
 * It intercepts nothing: it is just a background without handlers, taps pass through.
 *
 * @trace glass-design-system-21a3fe
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
  val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val stops = statusBarScrimStops(MaterialTheme.colorScheme.background, statusBarInset)
  // No status bar (landscape with it hidden, a Robolectric host, a desktop preview) — no band, otherwise a
  // stripe of background would hang over content for no reason.
  if (stops.isEmpty()) return
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(statusBarInset + FADE_TAIL)
      .background(Brush.verticalGradient(*stops.toTypedArray())),
  )
}

/**
 * The band's gradient, as a plain function so a test can read it. Rasterizing this under Robolectric and
 * sampling pixels was the first attempt and it hangs in `captureToImage`, so the thing worth guarding — "dense
 * all the way down the status bar, fading only BELOW it" — is stated here instead of inferred from an image.
 *
 * Returns an empty list when there is no status bar to cover.
 */
internal fun statusBarScrimStops(base: Color, inset: Dp, tail: Dp = FADE_TAIL): List<Pair<Float, Color>> {
  if (inset <= 0.dp) return emptyList()
  val total = inset + tail
  // Where the dense part ends and the fade begins, as a fraction of the whole band.
  val holdTo = (inset / total).coerceIn(0f, 1f)
  val plate = base.copy(alpha = GlassTokens.PlateAlpha)
  return listOf(0f to plate, holdTo to plate, 1f to Color.Transparent)
}

/**
 * How far below the status bar the band dissolves. A taste knob: longer — a softer transition but more of the
 * first line dimmed, shorter — a visible edge. 16dp is about one line of body text.
 */
internal val FADE_TAIL: Dp = 16.dp
