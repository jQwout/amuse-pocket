package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Меташар: загружает подробную инструкцию навыка по id (уровень 2 progressive disclosure).
 * Для агент-лупа это обычный Tool — результат уходит обратно в контекст как tool-message.
 * Грузить можно только зарегистрированные навыки (whitelist), произвольный текст/путь — нельзя.
 */
@Inject
@SingleIn(AppScope::class)
class LoadSkillTool(
  private val registry: SkillRegistry,
) : Tool {

  override val name = "load_skill"
  override val description =
    "Загрузить подробную инструкцию навыка по его id, когда задача подходит под навык из каталога доступных навыков."

  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "id" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("ID навыка из каталога доступных навыков"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("id"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val id = (arguments["id"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'id'")
    val skill = registry.get(id)
      ?: return ToolResult.Error(
        "Unknown skill: $id. Доступные: ${registry.ids().joinToString()}",
      )
    return ToolResult.Success(skill.instructions())
  }
}
