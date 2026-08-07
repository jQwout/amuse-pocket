package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Избранные модели — те, что пользователь отметил в окне выбора.
 *
 * Лежат в конфиге рядом с `genai-models.json`, а не в кеше: это решение пользователя, а не
 * скачанное. Слаги хранятся как есть, без сверки с каталогом: модель может временно пропасть из
 * живого списка (или переехать в свой `genai-models.json`), и вычищать её из избранного за это
 * нельзя — вернётся, а отметки уже нет.
 *
 * Битый файл читается как пустой список, и запись поверх него разрешена — в отличие от истории
 * заданий и заготовок промптов, где так делать запрещено. Разница предметная: там за файлом стоят
 * деньги и труд, а здесь — набор галочек, который восстанавливается двумя кликами. Отказаться писать
 * значило бы запереть избранное навсегда из-за одной лишней запятой.
 */
class FavoriteModels(private val file: File) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun read(): Set<String> {
    if (!file.isFile) return emptySet()
    return runCatching {
      json.decodeFromString<FavoriteModelsFile>(file.readText()).models.toSet()
    }.getOrDefault(emptySet())
  }

  /** Переключает отметку и сразу пишет на диск. Возвращает новый набор — его и показывает окно. */
  fun toggle(modelId: String): Set<String> {
    val current = read()
    val next = if (modelId in current) current - modelId else current + modelId
    write(next)
    return next
  }

  /**
   * Пишем рядом и переименовываем — по той же причине, что в `JobStore.save`: прямая запись сначала
   * обрезает файл до нуля, и читатель, попавший в это окно, увидел бы обрубок.
   */
  @Synchronized
  fun write(models: Set<String>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    runCatching {
      tmp.writeText(json.encodeToString(FavoriteModelsFile(models.toList())))
      if (!tmp.renameTo(file)) {
        // renameTo не перезаписывает существующий файл на Windows — сносим и повторяем.
        file.delete()
        if (!tmp.renameTo(file)) tmp.delete()
      }
    }.onFailure { runCatching { tmp.delete() } }
  }
}

@Serializable
internal data class FavoriteModelsFile(val models: List<String> = emptyList())
