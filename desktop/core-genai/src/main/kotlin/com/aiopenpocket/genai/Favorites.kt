package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Отмеченное звёздочкой — модели в одном файле, заготовки промптов в другом.
 *
 * Один класс на две сущности, а не две копии: хранится набор идентификаторов, и всё, что тут есть, —
 * читать, переключать и не потерять при записи. Заводить второй такой же класс ради другого имени
 * поля значило бы чинить будущие ошибки дважды.
 *
 * Лежат в конфиге рядом с `genai-models.json`, а не в кеше: это решение пользователя, а не
 * скачанное. Идентификаторы хранятся как есть, без сверки с каталогом или библиотекой: модель может
 * временно пропасть из живого списка, заготовка — переехать в другой файл, и вычищать их из
 * избранного за это нельзя — вернутся, а отметки уже нет.
 *
 * Битый файл читается как пустой список, и запись поверх него разрешена — в отличие от истории
 * заданий и заготовок промптов, где так делать запрещено. Разница предметная: там за файлом стоят
 * деньги и труд, а здесь — набор галочек, который восстанавливается двумя кликами. Отказаться писать
 * значило бы запереть избранное навсегда из-за одной лишней запятой.
 */
class Favorites(private val file: File) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun read(): Set<String> {
    if (!file.isFile) return emptySet()
    return runCatching {
      val stored = json.decodeFromString<FavoritesFile>(file.readText())
      (stored.ids + stored.models).toSet()
    }.getOrDefault(emptySet())
  }

  /** Переключает отметку и сразу пишет на диск. Возвращает новый набор — его и показывает окно. */
  fun toggle(id: String): Set<String> {
    val current = read()
    val next = if (id in current) current - id else current + id
    write(next)
    return next
  }

  /**
   * Пишем рядом и переименовываем — по той же причине, что в `JobStore.save`: прямая запись сначала
   * обрезает файл до нуля, и читатель, попавший в это окно, увидел бы обрубок.
   */
  @Synchronized
  fun write(ids: Set<String>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    runCatching {
      tmp.writeText(json.encodeToString(FavoritesFile(ids.toList())))
      if (!tmp.renameTo(file)) {
        // renameTo не перезаписывает существующий файл на Windows — сносим и повторяем.
        file.delete()
        if (!tmp.renameTo(file)) tmp.delete()
      }
    }.onFailure { runCatching { tmp.delete() } }
  }
}

@Serializable
internal data class FavoritesFile(
  val ids: List<String> = emptyList(),
  /**
   * Как поле называлось, пока избранное было только у моделей. Читается, но не пишется: иначе
   * переименование поля молча стёрло бы уже отмеченные модели — файл разобрался бы без ошибок, а
   * список оказался пуст. Строчка живёт ради тех, кто успел что-то отметить до этой правки.
   */
  val models: List<String> = emptyList(),
)
