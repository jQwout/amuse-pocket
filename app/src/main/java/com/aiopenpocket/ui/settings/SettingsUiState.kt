package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  val nvidiaKey: String = "",
  val openRouterKey: String = "",
  /** Куда уходят запросы агента. Меняется только руками — молчаливого переезда к другому вендору нет. */
  val provider: LlmProvider = LlmProvider.NIM,
  /** Конкретная модель ВЫБРАННОГО провайдера ("AUTO" — по приоритету из его каталога). */
  val selectedModel: String = "AUTO",
  /** Модели каталога выбранного провайдера, отсортированные по приоритету — опции для выбора. */
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
