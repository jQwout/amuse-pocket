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

/** Тёмная схема: лаймовый акцент по чернильным нейтралям. */
val AppDarkColors =
  darkColorScheme(
    primary = LimeSignal,
    onPrimary = LimeOnDark,
    primaryContainer = Color(0xFF2A3B00), // приглушённый лайм-контейнер
    onPrimaryContainer = Color(0xFFE6F5B8),
    background = InkBg,
    onBackground = InkText,
    surface = InkSurface,
    onSurface = InkText,
    surfaceVariant = InkSurface2,
    onSurfaceVariant = InkTextSub,
    outline = InkOutline,
    outlineVariant = Color(0x14FFFFFF),
    // error/onError — дефолты Material 3.
  )

/** Светлая схема: оливковый акцент по тёплой бумаге. */
val AppLightColors =
  lightColorScheme(
    primary = OliveAccent, // акцент на тексте/иконках
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF0A6), // светлый лайм-контейнер
    onPrimaryContainer = OliveOnLight,
    background = PaperBg,
    onBackground = PaperText,
    surface = PaperSurface,
    onSurface = PaperText,
    surfaceVariant = PaperSurface2,
    onSurfaceVariant = PaperTextSub,
    outline = PaperOutline,
    outlineVariant = Color(0x14000000),
    // error/onError — дефолты Material 3.
  )

/** Три режима переключателя темы. [SYSTEM] следует за системной настройкой. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * Тёмная ли сейчас тема — с учётом форс-режима (LIGHT/DARK перебивают систему).
 * Читают glass-слои (тинты/бордер/аврора), чтобы адаптироваться, не полагаясь на
 * [isSystemInDarkTheme] (та соврала бы при форс-режиме).
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
  // Иконки системных баров (часы, батарея, сеть) красим по ТЕМЕ ПРИЛОЖЕНИЯ, а не по системной.
  //
  // `enableEdgeToEdge()` в MainActivity вызывается в onCreate — до того, как известен выбранный
  // ThemeMode, — и по умолчанию решает светлые иконки или тёмные по системному dark mode. При своей
  // переключалке это врёт: выбрал Светлую при системной Тёмной — иконки остались светлыми, и часы с
  // зарядкой пропали на светлом фоне (ровно тот баг, что видно на устройстве). Здесь тема уже
  // разрешена (включая SYSTEM), поэтому источник правды один.
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      WindowCompat.getInsetsController(window, view).apply {
        // Светлая тема ⇒ светлый фон ⇒ иконки должны быть ТЁМНЫМИ (isAppearanceLight* = true).
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
