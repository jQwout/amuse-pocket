package com.aiopenpocket.agent.tools

import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class MemoryTools(
  private val memoryRepository: MemoryRepository,
) : Tool {

  override val name = "memory_tool"
  override val description = "Управление долговременной памятью агента: запоминать факты, искать факты, вспоминать эпизоды."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("remember_fact,search_memory,recall_episodes"),
            "description" to JsonPrimitive("Действие: remember_fact, search_memory, recall_episodes"),
          )),
          "key" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Ключ факта (для remember_fact)"),
          )),
          "value" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Значение факта (для remember_fact)"),
          )),
          "category" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Категория факта: profile, preference (для remember_fact)"),
          )),
          "query" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Запрос для поиска фактов (для search_memory)"),
          )),
          "range" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Диапазон для эпизодов: today, yesterday, last_week (для recall_episodes)"),
          )),
        )),
        "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'action'")
    return when (action) {
      "remember_fact" -> rememberFact(arguments)
      "search_memory" -> searchMemory(arguments)
      "recall_episodes" -> recallEpisodes(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun rememberFact(args: Map<String, JsonElement>): ToolResult {
    val key = (args["key"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'key'")
    val value = (args["value"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'value'")
    // Категорию задаёт пользователь (через модель) — это недоверенный ввод. Зажимаем в allowlist,
    // чтобы факт не попал в «привилегированную» секцию промпта (system/Инструкции/персона).
    val category = sanitizeCategory((args["category"] as? JsonPrimitive)?.content)
    val id = memoryRepository.saveFact(key, value, category)
    return ToolResult.Success("Fact saved: $key = $value (id=$id)")
  }

  /** Только эти категории модель может присваивать фактам. Прочее сводится к general. */
  private fun sanitizeCategory(raw: String?): String =
    when (raw?.trim()?.lowercase()) {
      "profile" -> "profile"
      "preference" -> "preference"
      else -> "general"
    }

  private suspend fun searchMemory(args: Map<String, JsonElement>): ToolResult {
    val query = (args["query"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'query'")
    val facts = memoryRepository.searchFacts(query)
    return if (facts.isEmpty()) {
      ToolResult.Success("No facts found for '$query'")
    } else {
      ToolResult.Success(facts.joinToString("\n") { "- ${it.key}: ${it.value} [${it.category}]" })
    }
  }

  private suspend fun recallEpisodes(args: Map<String, JsonElement>): ToolResult {
    val range = (args["range"] as? JsonPrimitive)?.content
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

  private fun startOfTodayMillis(): Long =
    java.util.Calendar.getInstance().apply {
      set(java.util.Calendar.HOUR_OF_DAY, 0)
      set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
