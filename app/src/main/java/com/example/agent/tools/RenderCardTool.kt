package com.aiopenpocket.agent.tools

// @trace driven-ui-cards-0418d0

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

private val CARD_TEMPLATES = listOf(
  "todo_list", "weather_card", "timer", "chart_bar", "table", "form", "choice", "lesson_complete",
  "switch", "notification_permission",
)

@Inject
@SingleIn(AppScope::class)
class RenderCardTool : Tool {

  override val name = "render_card"
  override val description =
    "Отображает интерактивную карточку в чате. Доступные шаблоны: ${CARD_TEMPLATES.joinToString(", ")}. " +
    "switch — переключатель вкл/выкл для настроек приложения, params: {label, description, action, initial}. " +
    "notification_permission — карточка запроса разрешения на уведомления, params необязательны: {title, description, button_label}."
  override val schema = toolSchema(name, description) {
    string("template", "ID шаблона карточки", required = true, enum = CARD_TEMPLATES)
    // params optional: some cards (e.g. notification_permission) take none, and execute tolerates
    // their absence. Marking it required would make the central schema check reject valid calls.
    obj("params", "Параметры шаблона (зависят от template)", required = false)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val template = arguments.string("template") ?: return missingArg("template")
    val params = arguments["params"].toString()
    return ToolResult.Success("""{"template":"$template","params":$params}""")
  }
}
