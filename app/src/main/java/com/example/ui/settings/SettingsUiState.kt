package com.aiopenpocket.ui.settings

data class SettingsUiState(
  val nvidiaKey: String = "",
  val groqKey: String = "",
  val githubKey: String = "",
  val selectedProvider: String = "AUTO",
  val personaId: String = "sharp_partner",
  val personas: List<String> = emptyList(),
  val saved: Boolean = false,
)
