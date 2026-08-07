package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Сколько раз какую заготовку вставляли.
 *
 * Считается с нуля и только вперёд: восстановить прошлые использования неоткуда — в истории заданий
 * лежит итоговый промпт, а не то, из какой заготовки он вырос. Гадать по совпадению текста значило
 * бы приписать заготовке чужие запуски, поэтому у всех сразу после обновления будет пусто, и это
 * честнее выдуманных чисел.
 *
 * Счётчик — не деньги и не труд: битый файл читается как «использований нет», и запись поверх
 * разрешена. Та же развилка и та же причина, что у [Favorites].
 */
class PromptUsage(private val file: File) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun read(): Map<String, Int> {
    if (!file.isFile) return emptyMap()
    return runCatching {
      json.decodeFromString<PromptUsageFile>(file.readText()).used
    }.getOrDefault(emptyMap())
  }

  /** +1 к заготовке. Возвращает новую карту — её и показывает окно. */
  fun increment(presetId: String): Map<String, Int> {
    val next = read().toMutableMap()
    next[presetId] = (next[presetId] ?: 0) + 1
    write(next)
    return next
  }

  /** Пишем рядом и переименовываем — как везде в модуле: прямая запись оставляет обрубок. */
  @Synchronized
  fun write(used: Map<String, Int>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    runCatching {
      tmp.writeText(json.encodeToString(PromptUsageFile(used)))
      if (!tmp.renameTo(file)) {
        // renameTo не перезаписывает существующий файл на Windows — сносим и повторяем.
        file.delete()
        if (!tmp.renameTo(file)) tmp.delete()
      }
    }.onFailure { runCatching { tmp.delete() } }
  }
}

@Serializable
internal data class PromptUsageFile(val used: Map<String, Int> = emptyMap())
