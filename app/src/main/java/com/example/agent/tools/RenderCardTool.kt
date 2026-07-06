package com.aiopenpocket.agent.tools

// @trace driven-ui-cards-0418d0

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
  override val description = "Отображает интерактивную карточку в чате. Доступные шаблоны: todo_list, weather_card, timer, chart_bar, table, form, choice, lesson_complete, switch, notification_permission. switch — переключатель вкл/выкл для настроек приложения, params: {label, description, action, initial}. notification_permission — карточка запроса разрешения на уведомления, params необязательны: {title, description, button_label}."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "template" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("todo_list"), JsonPrimitive("weather_card"), JsonPrimitive("timer"), JsonPrimitive("chart_bar"), JsonPrimitive("table"), JsonPrimitive("form"), JsonPrimitive("choice"), JsonPrimitive("lesson_complete"), JsonPrimitive("switch"), JsonPrimitive("notification_permission"))),
            "description" to JsonPrimitive("ID шаблона карточки"),
          )),
          "params" to JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "description" to JsonPrimitive("Параметры шаблона (зависят от template)"),
          )),
        )),
        // Only `template` is truly required: some cards (e.g. notification_permission) take no
        // params, and execute already tolerates their absence. Keeping `params` here would make the
        // central schema check reject those valid, params-less calls.
        "required" to kotlinx.serialization.json.JsonArray(listOf(
          JsonPrimitive("template"),
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
