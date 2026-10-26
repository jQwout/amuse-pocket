package com.aiopenpocket.data.repository

// @trace smena-temy-svetlaya-temnaya-sistemnaya-palitra-lime-ink-paper-005bc6

import android.content.Context
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the chosen theme mode (Light/Dark/System) and exposes it reactively. Not a secret — so plain
 * SharedPreferences rather than EncryptedSharedPreferences ([SettingsRepository]): no need to pull the
 * MasterKey initialization into startup for a theme choice.
 */
@SingleIn(AppScope::class)
@Inject
class ThemeRepository(
  context: Context,
) {

  private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

  private val _mode = MutableStateFlow(readMode())
  val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

  fun setMode(value: ThemeMode) {
    prefs.edit().putString(KEY, value.name).apply()
    _mode.value = value
  }

  /** String → enum; an unknown or missing value degrades to [ThemeMode.SYSTEM]. */
  private fun readMode(): ThemeMode =
    prefs.getString(KEY, null)
      ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
      ?: ThemeMode.SYSTEM

  private companion object {
    const val KEY = "theme_mode"
  }
}
