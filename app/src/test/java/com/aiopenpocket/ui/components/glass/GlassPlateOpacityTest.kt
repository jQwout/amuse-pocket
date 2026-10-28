package com.aiopenpocket.ui.components.glass

// @trace glass-design-system-21a3fe

import androidx.compose.ui.graphics.Color
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.InkBg
import com.aiopenpocket.ui.theme.InkText
import com.aiopenpocket.ui.theme.PaperBg
import com.aiopenpocket.ui.theme.PaperText
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The owner's criterion, in arithmetic: text passing under a floating pill must not be readable through it.
 *
 * There is no backdrop blur in this app — it was removed by the product owner's decision, and the only thing
 * between the feed and the bar above it is [GlassTokens.PlateAlpha] plus the tint film. On the device a line
 * of chat text ghosted through a pill and was mistaken for a weak blur. This computes what a reader actually
 * sees — the content composited through the plate and then the tint — and requires the result to sit below
 * reading contrast.
 *
 * The 1.06 gate is not folklore: at the old 0.94 plate the brightest case lands at about 1.15, and the
 * threshold where it drops under 1.06 is about 0.973, so this separates "the owner can see it" from "it is
 * gone" rather than rubber-stamping whatever the constant happens to be.
 */
class GlassPlateOpacityTest {

  @Test
  fun dark_theme_hides_what_scrolls_under_a_floating_plate() {
    // The brightest things that pass under a bar in the dark theme: ordinary message text, and a lesson
    // card, which is drawn in light stone whatever the theme (see DrivenUiCardViews).
    assertHidden("dark, message text", InkBg, GlassTokens.LightTintDark, InkText)
    assertHidden("dark, lesson card", InkBg, GlassTokens.LightTintDark, LESSON_CARD_STONE)
  }

  @Test
  fun light_theme_hides_what_scrolls_under_a_floating_plate() {
    assertHidden("light, message text", PaperBg, GlassTokens.LightTintLight, PaperText)
  }

  private fun assertHidden(case: String, background: Color, tint: Color, content: Color) {
    val asDrawn = seenThrough(background, tint, content)
    val asEmpty = seenThrough(background, tint, background)
    val ratio = contrastRatio(asDrawn, asEmpty)
    assertTrue(
      "$case: content shows through the plate at %.3f:1 — a reader can still make it out".format(ratio),
      ratio < 1.06,
    )
  }

  /** What ends up on screen where [content] lies under the plate: plate over content, then the tint over that. */
  private fun seenThrough(background: Color, tint: Color, content: Color): Color {
    val plated = over(background.copy(alpha = GlassTokens.PlateAlpha), content)
    return over(tint, plated)
  }
}

/** Source-over, in the gamma-encoded space the platform actually blends in. Both operands end up opaque. */
private fun over(top: Color, bottom: Color): Color {
  val a = top.alpha
  return Color(
    red = a * top.red + (1 - a) * bottom.red,
    green = a * top.green + (1 - a) * bottom.green,
    blue = a * top.blue + (1 - a) * bottom.blue,
  )
}

/** The light stone a lesson card is drawn in — the brightest surface that can scroll under a chat bar. */
private val LESSON_CARD_STONE = Color(0xFFE4E3D9)

private fun luminance(color: Color): Double {
  fun channel(v: Float): Double {
    val c = v.toDouble()
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
  }
  return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

private fun contrastRatio(a: Color, b: Color): Double {
  val la = luminance(a)
  val lb = luminance(b)
  return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
