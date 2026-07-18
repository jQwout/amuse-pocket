package com.aiopenpocket.ui.settings

// @trace app-settings-beb470

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  val nvidiaKey: String = "",
  val groqKey: String = "",
  val githubKey: String = "",
  /** Конкретная NIM-модель ("AUTO" — по приоритету из models.json). */
  val selectedModel: String = "AUTO",
  /** Модели из каталога (models.json), отсортированные по приоритету — опции для выбора. */
  val availableModels: List<String> = emptyList(),
  val personaId: String = "sharp_partner",
  val personas: List<String> = emptyList(),
  /** Лимит витков гоал-мода (петля ReAct до достижения цели). */
  val goalMaxIterations: Int = 3,
  /** Пресет размера шрифта сообщений в чате. */
  val chatFontScale: ChatFontScale = ChatFontScale.DEFAULT,
  /** Режим темы: Светлая/Тёмная/Системная. */
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val saved: Boolean = false,
)
