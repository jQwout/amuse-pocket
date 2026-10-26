package com.aiopenpocket.i18n

// @trace english-ui-on-the-demo-path-223241

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Язык интерфейса на весь процесс.
 *
 * Интерфейс пишется по-русски прямо в коде экранов, без строковых ресурсов Android; английский
 * встаёт рядом с русским через [tr] — сначала на пути демо хакатона. Перевод всего приложения на
 * ресурсы был бы правильнее, но не по срокам, а пара «ru, en» в одном вызове не даёт строкам
 * разъехаться при правке.
 *
 * [code] — снапшот-состояние Compose: экран, прочитавший [tr], перерисуется при смене языка без
 * пересоздания активности. Источник правды — `UserPreferencesRepository.language`; сюда его зеркалят
 * [com.aiopenpocket.AiOpenPocketApp] (для фона: уведомления, подписи инструментов) и MainActivity (до
 * первого кадра, чтобы экран не мигнул русским).
 */
object AppLanguage {
  const val RU = "ru"
  const val EN = "en"

  var code: String by mutableStateOf(RU)

  val isEnglish: Boolean get() = code == EN
}

/** Текст на языке интерфейса: [en], если выбран English, иначе [ru]. */
fun tr(ru: String, en: String): String = if (AppLanguage.isEnglish) en else ru
