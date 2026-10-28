package com.aiopenpocket.ui.theme

// @trace smena-temy-svetlaya-temnaya-sistemnaya-palitra-lime-ink-paper-005bc6

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The palette is lime and olive on ink and paper. Material's own defaults for the roles a scheme does NOT
 * spell out are purple, and for months [AppDarkColors]/[AppLightColors] left secondary and tertiary unset —
 * so the selected chip in the chat bar, the chips of the lesson scenes and the tool-trace panel were drawn
 * in Material purple inside a lime app. These tests fail if any accent role goes back to a default.
 *
 * "Purple" is read off the channels rather than compared to a fixed list of defaults: every Material purple
 * (and pink) has more blue than green, while nothing in a lime/olive/sand family does. A neutral (grey,
 * white, black) has them equal and passes — the neutrals are not what this guards.
 */
class AppColorSchemeTest {

  private fun ColorScheme.accentRoles(): Map<String, Color> = mapOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
  )

  @Test
  fun dark_scheme_has_no_purple_accent() = assertNoPurple("dark", AppDarkColors)

  @Test
  fun light_scheme_has_no_purple_accent() = assertNoPurple("light", AppLightColors)

  private fun assertNoPurple(scheme: String, colors: ColorScheme) {
    val purple = colors.accentRoles().filter { (_, color) -> color.blue > color.green }
    assertTrue(
      "$scheme scheme: ${purple.keys} are purple — a role left unset falls back to Material's own, " +
        "which is outside the lime/olive palette. Spell it out in Theme.kt.",
      purple.isEmpty(),
    )
  }

  /**
   * Secondary text — the heartbeat card's «06:59 · Сработало», the permission card's «Разреши уведомления…»
   * — is drawn with `onSurfaceVariant` on `surfaceVariant`. On the device it sat at the edge of legibility:
   * the old tones gave 6.5 (dark) and 5.8 (light), so the WCAG AAA floor for body text is the gate here.
   */
  @Test
  fun secondary_text_is_legible_on_its_surface() {
    listOf("dark" to AppDarkColors, "light" to AppLightColors).forEach { (name, colors) ->
      val ratio = contrastRatio(colors.onSurfaceVariant, colors.surfaceVariant)
      assertTrue(
        "$name scheme: onSurfaceVariant on surfaceVariant is only %.2f:1".format(ratio),
        ratio >= 7.0,
      )
    }
  }

  /** Text on a container must stay readable after the container's tone is chosen. */
  @Test
  fun container_text_is_legible() {
    listOf("dark" to AppDarkColors, "light" to AppLightColors).forEach { (name, colors) ->
      val pairs = listOf(
        "primaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
        "secondaryContainer" to (colors.onSecondaryContainer to colors.secondaryContainer),
        "tertiaryContainer" to (colors.onTertiaryContainer to colors.tertiaryContainer),
        "primary" to (colors.onPrimary to colors.primary),
        "secondary" to (colors.onSecondary to colors.secondary),
        "tertiary" to (colors.onTertiary to colors.tertiary),
      )
      pairs.forEach { (role, pair) ->
        val ratio = contrastRatio(pair.first, pair.second)
        assertTrue(
          "$name scheme: on$role over $role is only %.2f:1".format(ratio),
          ratio >= 4.5,
        )
      }
    }
  }
}

/** WCAG relative luminance. The colors here are all opaque, so alpha is not folded in. */
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
