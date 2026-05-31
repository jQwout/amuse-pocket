package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = PurpleAccent,
    onPrimary = PurpleAccentDark,
    primaryContainer = UserBubbleBg,
    onPrimaryContainer = UserBubbleFg,
    secondary = PurpleAccent,
    onSecondary = PurpleAccentDark,
    secondaryContainer = CardDark,
    onSecondaryContainer = DarkOnBg,
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkBg,
    onSurface = DarkOnBg,
    surfaceVariant = CardDark,
    onSurfaceVariant = DarkOnBg,
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = CancelRed,
    onError = CancelRedBg
  )

private val LightColorScheme = DarkColorScheme

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme for Elegant Dark look
  dynamicColor: Boolean = false, // Disable dynamic colors to ensure the exact theme palette is shown
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
