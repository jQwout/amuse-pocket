package com.aiopenpocket.ui.settings

// @trace app-settings-beb470

data class SettingsUiState(
  val nvidiaKey: String = "",
  val groqKey: String = "",
  val githubKey: String = "",
  val selectedProvider: String = "AUTO",
  val personaId: String = "sharp_partner",
  val personas: List<String> = emptyList(),
  /** Лимит витков гоал-мода (петля ReAct до достижения цели). */
  val goalMaxIterations: Int = 3,
  val saved: Boolean = false,
)
