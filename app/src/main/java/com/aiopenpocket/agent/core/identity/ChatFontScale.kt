package com.aiopenpocket.agent.core.identity

// @trace chat-font-size-setting-c72418

/**
 * Chat message font size presets chosen in Settings. [scale] is the multiplier applied to the bubble
 * text size ([com.aiopenpocket.ui.components.MarkdownText]); it works ON TOP of the device's system
 * `fontScale` and applies only to message text.
 *
 * Stored by [name] (as a string) in DataStore — reordering the presets does not break an already saved
 * choice; [fromName] resolves an unknown/old value to [DEFAULT] instead of crashing the app. The
 * multipliers are chosen so XLARGE stays readable in a bubble without breaking the layout: the base
 * `bodySmall` (~14sp) gives a range of ≈12–18sp.
 */
enum class ChatFontScale(val scale: Float, val label: String) {
  SMALL(0.85f, "Мелкий"),
  MEDIUM(1.0f, "Обычный"),
  LARGE(1.15f, "Крупный"),
  XLARGE(1.30f, "Очень крупный");

  companion object {
    val DEFAULT = MEDIUM

    /** A robust parse of the saved name: an unknown/`null`/old value → [DEFAULT]. */
    fun fromName(name: String?): ChatFontScale =
      entries.firstOrNull { it.name == name } ?: DEFAULT
  }
}
