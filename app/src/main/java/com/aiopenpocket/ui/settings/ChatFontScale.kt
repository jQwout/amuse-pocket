package com.aiopenpocket.ui.settings

// @trace chat-font-size-setting-c72418

/**
 * Пресеты размера шрифта сообщений в чате, выбираемые в Настройках. [scale] — множитель, на который
 * умножается кегль текста пузыря ([com.aiopenpocket.ui.components.MarkdownText]); надстройка НАД
 * системным `fontScale` устройства, применяется только к тексту сообщений.
 *
 * Хранится по [name] (строкой) в DataStore — переупорядочивание пресетов не ломает уже сохранённый
 * выбор; неизвестное/старое значение [fromName] разрешает в [DEFAULT], а не роняет приложение.
 * Множители подобраны так, чтобы XLARGE оставался читаемым в пузыре, не ломая верстку: базовый
 * `bodySmall` (~14sp) даёт диапазон ≈12–18sp.
 */
enum class ChatFontScale(val scale: Float, val label: String) {
  SMALL(0.85f, "Мелкий"),
  MEDIUM(1.0f, "Обычный"),
  LARGE(1.15f, "Крупный"),
  XLARGE(1.30f, "Очень крупный");

  companion object {
    val DEFAULT = MEDIUM

    /** Устойчивый разбор сохранённого имени: неизвестное/`null`/старое значение → [DEFAULT]. */
    fun fromName(name: String?): ChatFontScale =
      entries.firstOrNull { it.name == name } ?: DEFAULT
  }
}
