package com.aiopenpocket.agent.core.prompt

// @trace agent-memory-05e545

import com.aiopenpocket.agent.core.memory.model.Episode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Эпизод памяти одной строкой для модели: `[инструмент действие] начало результата`.
 *
 * Эпизод хранит результат вызова инструмента ЦЕЛИКОМ (чтение файла — до 40 тыс. символов, ответ
 * хранилища — до 100 тыс.), а «Недавние действия» подставляются в КАЖДЫЙ системный промпт диалога.
 * Сырой результат значил, что один прочитанный файл ехал в каждый следующий ход, пока его не вытеснят
 * пять более свежих вызовов. Модели от события нужен сам факт — «будильник уже поставлен», «файл
 * прочитан» — а не содержимое: оно, если понадобится, читается заново. Поэтому обрезаем ПРИ
 * ПОКАЗЕ, а не при записи: в базе остаётся полный след для аудита.
 */
object EpisodeFormat {

  /** Сколько символов результата показываем модели: хватает на «готово/ошибка + суть». */
  const val OUTCOME_MAX_CHARS = 300

  private val WHITESPACE = Regex("\\s+")

  fun line(episode: Episode): String = "[${label(episode)}] ${shortOutcome(episode.outcome)}"

  /** Результат в одну строку (переводы строк ломали бы список) и не длиннее [OUTCOME_MAX_CHARS]. */
  fun shortOutcome(outcome: String): String {
    val flat = outcome.replace(WHITESPACE, " ").trim()
    return if (flat.length <= OUTCOME_MAX_CHARS) flat else flat.take(OUTCOME_MAX_CHARS).trimEnd() + "…"
  }

  /**
   * Имя инструмента и его `action` из payload эпизода: голый тип `tool_call` модели ничего не
   * говорит — «что именно сделано» и есть полезная часть события. Нечитаемый payload — не повод
   * терять строку, тогда метка — просто тип эпизода.
   */
  private fun label(episode: Episode): String {
    val payload = runCatching { Json.parseToJsonElement(episode.payload) as? JsonObject }.getOrNull()
      ?: return episode.type
    val name = (payload["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return episode.type
    val action = ((payload["arguments"] as? JsonObject)?.get("action") as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() }
    return if (action == null) name else "$name $action"
  }
}
