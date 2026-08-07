package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Настройки приложения, которые надо пережить перезапуск, — те, что не файл и не ключ.
 *
 * Пока здесь одно поле. Отдельный тип, а не булев флаг в контейнере, ровно потому, что поле
 * появилось не последним: следующая настройка допишется сюда, а не заведёт второй файл рядом.
 */
@Serializable
data class AppSettings(
  /**
   * Журнал запросов. Выключен по умолчанию и остаётся выключенным, пока не попросят: он собирает
   * тела запросов, то есть все промпты пользователя.
   */
  val requestJournal: Boolean = false,
)

/**
 * Настройки на диске. В `configDir` рядом с ключом и своим списком моделей: это выбор пользователя,
 * а не скачанное.
 *
 * Битый файл читается как настройки по умолчанию, и запись поверх него разрешена — та же развилка,
 * что у [FavoriteModels], и по той же причине: за файлом стоит одна галочка, а не деньги и не труд,
 * и запирать её из-за лишней запятой дороже, чем потерять.
 */
class AppSettingsStore(private val file: File) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun read(): AppSettings {
    if (!file.isFile) return AppSettings()
    return runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())
  }

  /** Пишем рядом и переименовываем — как везде в этом модуле: прямая запись оставляет обрубок. */
  @Synchronized
  fun write(settings: AppSettings) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    runCatching {
      tmp.writeText(json.encodeToString(settings))
      if (!tmp.renameTo(file)) {
        // renameTo не перезаписывает существующий файл на Windows — сносим и повторяем.
        file.delete()
        if (!tmp.renameTo(file)) tmp.delete()
      }
    }.onFailure { runCatching { tmp.delete() } }
  }
}
