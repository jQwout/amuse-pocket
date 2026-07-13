package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.loop.TurnContextHolder
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class MemoryTools(
  private val memoryRepository: MemoryRepository,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  override val name = "memory_tool"
  override val description = "Управление долговременной памятью агента: запоминать факты, искать факты, вспоминать эпизоды."
  override val schema = toolSchema(name, description) {
    string("action", "Действие: remember_fact, search_memory, recall_episodes", required = true,
      enum = listOf("remember_fact", "search_memory", "recall_episodes"))
    string("key", "Ключ факта (для remember_fact)")
    string("value", "Значение факта (для remember_fact)")
    string("category", "Категория факта: profile, preference (для remember_fact)")
    string("query", "Запрос для поиска фактов (для search_memory)")
    string("range", "Диапазон для эпизодов: today, yesterday, last_week (для recall_episodes)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    return when (action) {
      "remember_fact" -> rememberFact(arguments)
      "search_memory" -> searchMemory(arguments)
      "recall_episodes" -> recallEpisodes(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun rememberFact(args: Map<String, JsonElement>): ToolResult {
    val key = args.string("key") ?: return missingArg("key")
    val value = args.string("value") ?: return missingArg("value")
    // Изоляция памяти агента — IN CODE, а не промпт-инструкцией (Task 5): пока в ходе привязан
    // агент со своим namespace, ключ ВСЕГДА уходит под него, что бы модель ни попросила. Без
    // привязанного агента (обычный чат) поведение не меняется ни на йоту — это ЕДИНСТВЕННЫЙ путь,
    // который обязан остаться глобальным.
    val agent = turnContextHolder.agent
    val storedKey = agent?.memoryPolicy?.namespace?.plus(key) ?: key
    // Категорию для обычного чата задаёт пользователь (через модель) — недоверенный ввод, зажатый в
    // allowlist. Для агента со своим namespace категория ПРИНУДИТЕЛЬНО general: profile/preference —
    // это общий (shared) профиль пользователя, который агент может только читать (см.
    // PrePromptAssembler.includeSharedProfile), а не дописывать туда через remember_fact.
    val category = if (agent != null) "general" else sanitizeCategory(args.string("category"))
    val id = memoryRepository.saveFact(storedKey, value, category)
    return ToolResult.Success("Fact saved: $storedKey = $value (id=$id)")
  }

  /** Только эти категории модель может присваивать фактам. Прочее сводится к general. */
  private fun sanitizeCategory(raw: String?): String =
    when (raw?.trim()?.lowercase()) {
      "profile" -> "profile"
      "preference" -> "preference"
      else -> "general"
    }

  private suspend fun searchMemory(args: Map<String, JsonElement>): ToolResult {
    val query = args.string("query") ?: return missingArg("query")
    // Тот же гейт, что в rememberFact: с bound-агентом поиск не выходит за его namespace — на
    // уровне DAO/WHERE (MemoryRepository.searchFactsInNamespace), а не постфильтром результата
    // модели. Обычный чат (agent == null) ищет глобально, как и раньше.
    val namespace = turnContextHolder.agent?.memoryPolicy?.namespace
    val facts = if (namespace != null) {
      memoryRepository.searchFactsInNamespace(namespace, query)
    } else {
      memoryRepository.searchFacts(query)
    }
    return if (facts.isEmpty()) {
      ToolResult.Success("No facts found for '$query'")
    } else {
      ToolResult.Success(facts.joinToString("\n") { "- ${it.key}: ${it.value} [${it.category}]" })
    }
  }

  private suspend fun recallEpisodes(args: Map<String, JsonElement>): ToolResult {
    val range = args.string("range")
    val dayMillis = 24L * 60 * 60 * 1000
    val startOfToday = startOfTodayMillis()
    val episodes = when (range) {
      "today" -> memoryRepository.getEpisodesSince(startOfToday)
      // Вчера — это календарное окно [начало вчера, начало сегодня), а не последние 24 часа.
      "yesterday" -> memoryRepository.getEpisodesBetween(startOfToday - dayMillis, startOfToday)
      "last_week" -> memoryRepository.getEpisodesSince(System.currentTimeMillis() - 7 * dayMillis)
      else -> memoryRepository.getEpisodesSince(System.currentTimeMillis() - dayMillis)
    }
    return if (episodes.isEmpty()) {
      ToolResult.Success("No episodes found for range '${range ?: "default"}'")
    } else {
      ToolResult.Success(episodes.joinToString("\n") { "- [${it.type}] ${it.outcome}" })
    }
  }

}
