package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A soft backing exactly the height of the system status bar: the theme's background color at the top,
 * transparency at the bottom.
 *
 * Why. The app draws edge-to-edge, so the content slides right under the clock, the battery and the network.
 * Even with the icon color chosen right (fixed by [com.aiopenpocket.ui.theme.MyApplicationTheme], which syncs
 * `isAppearanceLightStatusBars` with the APP's theme rather than the system's), on a busy spot — a light card,
 * an image, a bright aurora spot — they still get lost.
 *
 * Why a gradient and not a strip. A solid plate would bring back a visual "status bar" and kill the very idea
 * of "content takes all the space": the screen would again start with someone else's panel. A gradient to
 * transparency gives contrast where it is needed (right at the icons) and dissolves towards the content.
 *
 * It intercepts nothing: it is just a background without handlers, taps pass through.
 *
 * @trace glass-design-system-21a3fe
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
  val base = MaterialTheme.colorScheme.background
  Box(
    modifier = modifier
      .fillMaxWidth()
      .windowInsetsTopHeight(WindowInsets.statusBars)
      .background(
        Brush.verticalGradient(
          colors = listOf(base.copy(alpha = SCRIM_TOP_ALPHA), Color.Transparent),
        ),
      ),
  )
}

/**
 * The density at the very edge of the screen. A taste knob: higher — the clock reads more reliably on a busy
 * background, lower — the transition is less noticeable. 0.7 was picked as "always visible, but not read as a strip".
 */
private const val SCRIM_TOP_ALPHA = 0.7f
