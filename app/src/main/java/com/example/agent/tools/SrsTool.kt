package com.aiopenpocket.agent.tools

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.srs.SrsRepository
import com.aiopenpocket.agent.core.srs.SrsScheduler
import com.aiopenpocket.agent.core.srs.SrsWord
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Spaced-repetition vocabulary store for the language tutor. Owns the interval math ([SrsScheduler]);
 * persistence lives in [SrsRepository] (a dedicated `srs_words` Room table). The model only decides
 * whether an answer was correct — it never computes dates or hand-edits card state.
 */
@Inject
@SingleIn(AppScope::class)
class SrsTool(
  private val srsRepository: SrsRepository,
) : Tool {

  override val name = "srs_tool"
  override val description =
    "Интервальные повторения слов (SRS). Даты и интервалы считает сам инструмент — не в уме."
  override val schema = toolSchema(name, description) {
    string("action", "add_word (добавить слово), due_words (что повторить сегодня), " +
      "review (отметить ответ), all_words (все слова с этапами)", required = true,
      enum = listOf("add_word", "due_words", "review", "all_words"))
    string("lang", "Код изучаемого языка: en, de, fr, es, it… (для add_word/review; " +
      "необязателен для due_words/all_words — тогда по всем языкам)")
    string("term", "Слово или фраза на изучаемом языке (для add_word/review)")
    string("translation", "Перевод (для add_word)")
    string("example", "Пример употребления (необязательно, для add_word)")
    string("correct", "true, если пользователь ответил верно, иначе false (для review)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult =
    when (val action = arguments.string("action")) {
      "add_word" -> addWord(arguments)
      "due_words" -> dueWords(arguments)
      "review" -> review(arguments)
      "all_words" -> allWords(arguments)
      null -> ToolResult.Error("Missing 'action'")
      else -> ToolResult.Error("Unknown action: $action")
    }

  private suspend fun addWord(args: Map<String, JsonElement>): ToolResult {
    val lang = args.string("lang") ?: return ToolResult.Error("Missing 'lang'")
    val term = args.string("term") ?: return ToolResult.Error("Missing 'term'")
    val translation = args.string("translation") ?: return ToolResult.Error("Missing 'translation'")
    val word = SrsWord(
      lang = lang,
      term = term,
      translation = translation,
      example = args.string("example").orEmpty(),
      stage = 0,
      dueMs = startOfTodayMillis(), // due immediately for the first review
    )
    srsRepository.upsert(word)
    return ToolResult.Success("Добавлено слово «${term.trim()}» (${translation.trim()}). Повтор — сегодня.")
  }

  private suspend fun dueWords(args: Map<String, JsonElement>): ToolResult {
    val today = startOfTodayMillis()
    val due = srsRepository.list(args.string("lang"))
      .filter { SrsScheduler.isDue(it.dueMs, today) }
      .take(DUE_LIMIT)
    return if (due.isEmpty()) {
      ToolResult.Success("Сегодня повторять нечего — все слова на будущие даты.")
    } else {
      ToolResult.Success(
        "К повторению (${due.size}):\n" +
          due.joinToString("\n") { "- ${it.term} — ${it.translation}" },
      )
    }
  }

  private suspend fun review(args: Map<String, JsonElement>): ToolResult {
    val lang = args.string("lang") ?: return ToolResult.Error("Missing 'lang'")
    val term = args.string("term") ?: return ToolResult.Error("Missing 'term'")
    val correct = args.string("correct")?.let(::parseBool)
      ?: return ToolResult.Error("'correct' must be true or false")
    val word = srsRepository.get(lang, term)
      ?: return ToolResult.Error("Слово не найдено: $term. Сначала add_word.")
    val newStage = SrsScheduler.nextStage(word.stage, correct)
    val nextDue = SrsScheduler.nextDueMillis(startOfTodayMillis(), newStage)
    srsRepository.upsert(word.copy(stage = newStage, dueMs = nextDue))
    val days = SrsScheduler.intervalDaysForStage(newStage)
    val verdict = if (correct) "верно" else "неверно, повторим скоро"
    return ToolResult.Success("«${word.term}»: $verdict. Следующий повтор через $days дн. (этап $newStage).")
  }

  private suspend fun allWords(args: Map<String, JsonElement>): ToolResult {
    val words = srsRepository.list(args.string("lang"))
    if (words.isEmpty()) return ToolResult.Success("Пока нет сохранённых слов.")
    val byStage = IntArray(SrsScheduler.MAX_STAGE + 1)
    words.forEach { byStage[it.stage.coerceIn(0, SrsScheduler.MAX_STAGE)]++ }
    val stages = byStage.mapIndexed { stage, count -> "этап $stage: $count" }.joinToString(", ")
    return ToolResult.Success(
      "Всего слов: ${words.size} ($stages)\n" +
        words.joinToString("\n") { "- ${it.term} — ${it.translation} [этап ${it.stage}]" },
    )
  }

  /** Lenient truthiness: models do not always emit exactly "true"/"false". */
  private fun parseBool(raw: String): Boolean? = when (raw.trim().lowercase()) {
    "true", "yes", "1", "да", "верно" -> true
    "false", "no", "0", "нет", "неверно" -> false
    else -> null
  }

  companion object {
    private const val DUE_LIMIT = 20
  }
}
