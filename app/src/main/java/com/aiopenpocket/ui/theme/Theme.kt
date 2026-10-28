package com.aiopenpocket.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// @trace smena-temy-svetlaya-temnaya-sistemnaya-palitra-lime-ink-paper-005bc6

/**
 * The dark scheme: a lime accent on ink neutrals.
 *
 * Every accent role is spelled out, secondary and tertiary included. Leaving them unset does not leave them
 * empty — Material fills them with its own purple, and on the device that purple came through everywhere the
 * app draws via a secondary role it never chose on purpose: the selected chip in the chat bar, the quiz
 * scenes' chips, the tool-trace panel. The comment here used to excuse only error/onError, so the gap read as
 * deliberate for months. If a role is not listed below, it IS Material's default — check that before adding a
 * component that uses it.
 */
val AppDarkColors =
  darkColorScheme(
    primary = LimeSignal,
    onPrimary = LimeOnDark,
    primaryContainer = Color(0xFF2A3B00), // a muted lime container
    onPrimaryContainer = Color(0xFFE6F5B8),
    secondary = SageSignal,
    onSecondary = SageOnDark,
    secondaryContainer = Color(0xFF333A26), // dark moss — the selected filter chip, the tool-trace panel
    onSecondaryContainer = Color(0xFFDCE8C4),
    tertiary = SandSignal,
    onTertiary = SandOnDark,
    tertiaryContainer = Color(0xFF413721), // dark warm khaki
    onTertiaryContainer = Color(0xFFF5E4BE),
    background = InkBg,
    onBackground = InkText,
    surface = InkSurface,
    onSurface = InkText,
    surfaceVariant = InkSurface2,
    onSurfaceVariant = InkTextSub,
    outline = InkOutline,
    outlineVariant = Color(0x14FFFFFF),
    // error/onError are Material 3 defaults.
  )

/** The light scheme: an olive accent on warm paper. The same rule as [AppDarkColors] about unset roles. */
val AppLightColors =
  lightColorScheme(
    primary = OliveAccent, // the accent on text/icons
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF0A6), // a light lime container
    onPrimaryContainer = OliveOnLight,
    secondary = SageAccent,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6CA), // light sage — the selected filter chip, the tool-trace panel
    onSecondaryContainer = Color(0xFF2A3417),
    tertiary = SandAccent,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E3BE), // light warm sand
    onTertiaryContainer = Color(0xFF3A2B06),
    background = PaperBg,
    onBackground = PaperText,
    surface = PaperSurface,
    onSurface = PaperText,
    surfaceVariant = PaperSurface2,
    onSurfaceVariant = PaperTextSub,
    outline = PaperOutline,
    outlineVariant = Color(0x14000000),
    // error/onError are Material 3 defaults.
  )

/** The three modes of the theme switch. [SYSTEM] follows the system setting. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * Whether the theme is dark right now — taking the forced mode into account (LIGHT/DARK override the system).
 * The glass layers (tints/border/aurora) read it to adapt, without relying on [isSystemInDarkTheme] (which
 * would lie in a forced mode).
 */
val LocalThemeIsDark = staticCompositionLocalOf { true }

@Composable
fun MyApplicationTheme(
  mode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val dark =
    when (mode) {
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
  // The system bar icons (clock, battery, network) are colored by the APP'S THEME, not the system's.
  //
  // `enableEdgeToEdge()` in MainActivity is called in onCreate — before the chosen ThemeMode is known — and by
  // default decides between light and dark icons by the system dark mode. With our own switch that lies:
  // choose Light while the system is Dark — the icons stayed light, and the clock and the battery disappeared
  // on the light background (exactly the bug seen on the device). Here the theme is already resolved
  // (including SYSTEM), so there is a single source of truth.
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      WindowCompat.getInsetsController(window, view).apply {
        // A light theme ⇒ a light background ⇒ the icons must be DARK (isAppearanceLight* = true).
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
      }
    }
  }

  CompositionLocalProvider(LocalThemeIsDark provides dark) {
    MaterialTheme(
      colorScheme = if (dark) AppDarkColors else AppLightColors,
      typography = Typography,
      content = content,
    )
  }
}
