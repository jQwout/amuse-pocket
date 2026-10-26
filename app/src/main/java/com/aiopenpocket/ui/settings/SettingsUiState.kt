package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.activeLlmProvider
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  val openRouterKey: String = "",
  /** The Meta Model API key. Non-empty — requests go straight to Meta (see [activeLlmProvider]). */
  val metaKey: String = "",
  /** The model of the OpenRouter route ("AUTO" — by priority from the catalog). */
  val selectedModel: String = "AUTO",
  /** The model of the direct Meta route — a choice of its own, see SettingsRepository.getSelectedModel. */
  val selectedMetaModel: String = "AUTO",
  /** Meta models from the OpenRouter catalog, sorted by priority. */
  val openRouterModels: List<String> = emptyList(),
  /** Models of the direct Meta Model API (a static catalog), sorted by priority. */
  val metaModels: List<String> = emptyList(),
  val personaId: String = "sharp_partner",
  val personas: List<String> = emptyList(),
  /** The goal-mode iteration limit (the ReAct loop until the goal is reached). */
  val goalMaxIterations: Int = 3,
  /** The chat message font size preset. */
  val chatFontScale: ChatFontScale = ChatFontScale.DEFAULT,
  /** The theme mode: Light/Dark/System. */
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  /** Spend over the last week. `null` — not read from disk yet. */
  val usage: UsageSummary? = null,
  val saved: Boolean = false,
) {
  /**
   * Where requests will go with THE keys that are in the fields right now — even before "Save": the screen
   * shows at once what a tap will change, and the model list switches together with the route line.
   */
  val provider: LlmProvider get() = activeLlmProvider(metaKey)

  /** The model selector's options — the catalog of the current [provider]. */
  val availableModels: List<String>
    get() = if (provider == LlmProvider.META) metaModels else openRouterModels

  /** The selected model of the current [provider]. */
  val currentModel: String
    get() = if (provider == LlmProvider.META) selectedMetaModel else selectedModel
}

/**
 * Spend over a period — what the user sees and what pricing is computed from.
 *
 * The background is a separate field, not hidden in the total: calls outside a turn (the conversation
 * annotator, profile extraction, heartbeat) were not ordered by the user and are not seen by them, and it is
 * exactly their share that answers "why does the bill grow when I am doing nothing".
 */
data class UsageSummary(
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  /** `null` — no call of the period named a price (the provider gave only tokens). */
  val costUsd: Double?,
  /** How many calls were counted by estimate rather than by the provider's measurement — a measure of trust in the figures above. */
  val estimatedCalls: Int,
  val backgroundCalls: Int,
  val backgroundTokens: Long,
  val byModel: List<ModelUsage>,
) {
  val totalTokens: Long get() = promptTokens + completionTokens

  data class ModelUsage(
    val model: String,
    val calls: Int,
    val tokens: Long,
    val costUsd: Double?,
  )
}
