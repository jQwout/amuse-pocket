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
 * Glassmorphism tokens. The tints and the highlight border depend on the theme ([LocalThemeIsDark]): in the
 * dark one — a light film over ink, in the light one — a slight darkening over paper. The structural values
 * (blur radius, shape, aurora geometry) do not depend on the theme and stay static.
 * @trace glass-design-system-21a3fe
 */
object GlassTokens {

  // Raw tints per theme are named constants, so the invariant "strong glass is denser than weak" is checked by
  // a unit test without composition (the getters below only pick by theme).
  internal val StrongTintDark = Color.White.copy(alpha = 0.10f)
  internal val StrongTintLight = Color.Black.copy(alpha = 0.06f)
  internal val LightTintDark = Color.White.copy(alpha = 0.06f)
  internal val LightTintLight = Color.Black.copy(alpha = 0.04f)

  /** The tint of "strong" glass (bars/navigation). A light film on dark, a darkening on light. */
  val StrongTint: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalThemeIsDark.current) StrongTintDark else StrongTintLight

  /** The tint of "weak" glass (cards in a scroll, without blur). */
  val LightTint: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalThemeIsDark.current) LightTintDark else LightTintLight

  /** The backdrop blur radius of strong glass. */
  val BlurRadius: Dp = 20.dp

  /** The default card shape. */
  val CardShape: Shape = RoundedCornerShape(20.dp)

  /** The highlight on the edge: lighter at the top, fading downwards (on light — a dark hairline outline). */
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

  /** An aurora spot; center/radiusFraction are fractions of the canvas size (0..1). */
  data class AuroraSpot(val color: Color, val center: Offset, val radiusFraction: Float)

  /**
   * 3 static spots of the dark aurora: a soft lime at the top left, a deep olive on the right, a dark green
   * depth at the bottom. In the light theme the aurora is switched off in [Modifier.auroraBackground] (an
   * empty list), so the spots are defined only for the dark backing.
   */
  val AuroraSpots: List<AuroraSpot> = listOf(
    // The top-left light spot is muted (0.14): at a higher alpha the near-white text of headings/descriptions
    // at the top of screens lost contrast. The aurora stays, just softer.
    AuroraSpot(LimeSignal.copy(alpha = 0.14f), Offset(0.15f, 0.10f), 0.60f),
    AuroraSpot(OliveAccent.copy(alpha = 0.42f), Offset(0.90f, 0.25f), 0.70f),
    AuroraSpot(Color(0xFF10261B).copy(alpha = 0.55f), Offset(0.50f, 0.95f), 0.90f),
  )
}
