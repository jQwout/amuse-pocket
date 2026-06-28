package com.aiopenpocket.voice.input

// @trace voice-input-1a0dfd

import java.util.Locale

internal fun appLanguageToBcp47(code: String): String = when (code.lowercase(Locale.ROOT)) {
  "ru" -> "ru-RU"
  "en" -> "en-US"
  "de" -> "de-DE"
  else -> runCatching { Locale.forLanguageTag(code).toLanguageTag() }
    .getOrNull()
    ?.takeUnless { it.isBlank() || it == "und" }
    ?: "en-US"
}
