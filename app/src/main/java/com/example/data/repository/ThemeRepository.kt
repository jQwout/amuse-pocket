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
 * Хранит выбранный режим темы (Светлая/Тёмная/Системная) и отдаёт его реактивно.
 * Не секрет — поэтому обычные SharedPreferences, а не EncryptedSharedPreferences
 * ([SettingsRepository]): не тянем инициализацию MasterKey на старте ради выбора темы.
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

  /** Строка → enum; неизвестное или отсутствующее значение деградирует в [ThemeMode.SYSTEM]. */
  private fun readMode(): ThemeMode =
    prefs.getString(KEY, null)
      ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
      ?: ThemeMode.SYSTEM

  private companion object {
    const val KEY = "theme_mode"
  }
}
