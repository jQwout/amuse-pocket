package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.ui.theme.ThemeMode

data class SettingsUiState(
  /** The Meta Model API key the person entered. Empty — no model call can go out (see ModelKeyGate). */
  val metaKey: String = "",
  /** The chosen Meta model ("AUTO" — by priority from the catalog). */
  val selectedMetaModel: String = "AUTO",
  /** Models of the Meta Model API (a static catalog), sorted by priority. */
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
  /** Whether the key field holds a key right now — even before "Save": the status line reacts as the person types. */
  val hasKey: Boolean get() = metaKey.isNotBlank()

  /** The model selector's options. */
  val availableModels: List<String> get() = metaModels

  /** The selected model. */
  val currentModel: String get() = selectedMetaModel
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
  ) {
    /**
     * Billed per picture, not per token (SAM): no tokens, but money. Ranked by tokens such a row is always last, and
     * the card shows only the top few — the one call in a fresh demo that actually cost money would not be on it.
     */
    val billedPerPicture: Boolean get() = tokens == 0L && (costUsd ?: 0.0) > 0.0
  }
}
