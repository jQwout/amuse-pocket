package com.aiopenpocket.i18n

// @trace english-ui-on-the-demo-path-223241

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The interface language for the whole process.
 *
 * The interface is written in Russian right in the screens' code, without Android string resources;
 * English sits next to Russian through [tr] — first on the hackathon demo path. Moving the whole app to
 * resources would be more correct, but does not fit the schedule, and a "ru, en" pair in one call keeps
 * the strings from drifting apart when edited.
 *
 * [code] is Compose snapshot state: a screen that read [tr] recomposes when the language changes,
 * without recreating the activity. The source of truth is `UserPreferencesRepository.language`; it is
 * mirrored here by [com.aiopenpocket.AiOpenPocketApp] (for the background: notifications, tool
 * captions) and by MainActivity (before the first frame, so the screen does not flash Russian).
 */
object AppLanguage {
  const val RU = "ru"
  const val EN = "en"

  var code: String by mutableStateOf(RU)

  val isEnglish: Boolean get() = code == EN
}

/** Text in the interface language: [en] if English is selected, otherwise [ru]. */
fun tr(ru: String, en: String): String = if (AppLanguage.isEnglish) en else ru
