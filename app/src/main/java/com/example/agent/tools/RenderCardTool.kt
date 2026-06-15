package com.aiopenpocket.agent.tools

import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class RenderCardTool : Tool {

  override val name = "render_card"
  override val description = "Отображает интерактивную карточку в чате. Доступные шаблоны: todo_list, weather_card, timer, chart_bar, table, form, choice, lesson_complete."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "template" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("todo_list,weather_card,timer,chart_bar,table,form,choice,lesson_complete"),
            "description" to JsonPrimitive("ID шаблона карточки"),
          )),
          "params" to JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "description" to JsonPrimitive("Параметры шаблона (зависят от template)"),
          )),
        )),
        "required" to kotlinx.serialization.json.JsonArray(listOf(
          JsonPrimitive("template"),
          JsonPrimitive("params"),
        )),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val template = (arguments["template"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'template'")
    val params = arguments["params"].toString()
    val json = """{"template":"$template","params":$params}"""
    return ToolResult.Success(json)
  }
}
