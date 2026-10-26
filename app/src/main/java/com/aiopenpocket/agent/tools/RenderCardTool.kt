package com.aiopenpocket.agent.tools

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

/**
 * General-purpose templates any agent (preset or user-built) may render — not tied to the AI
 * Academy lesson flow. This is the subset a builder-created agent is expected to be scoped to by
 * default (see [com.aiopenpocket.agent.core.agent.AgentDefinition.allowedWidgetTemplates]), though
 * [RenderCardTool] itself does not hardcode that default — it only enforces whichever allowlist a
 * bound agent was actually given.
 */
val GENERAL_CARD_TEMPLATES = setOf(
  "todo_list", "timer", "chart_bar", "table", "form", "choice",
  "notification_permission", "reminder", "image",
)

/**
 * Templates that are NO LONGER offered to the model: `switch` has no action at all (the handler in
 * ChatScreen is empty — the toggle switched nothing), and `weather_card` has no weather source (the
 * model made up the forecast). The parsers in DrivenUiEngine stay so old messages still render. They
 * also stay in
 * [AgentValidator.KNOWN_WIDGET_TEMPLATES][com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_WIDGET_TEMPLATES]
 * for now, so an already saved agent with them in its allowlist does not stop publishing.
 */
val RETIRED_CARD_TEMPLATES = setOf("switch", "weather_card")

/**
 * Templates the AI Academy lesson flow renders
 * ([com.aiopenpocket.agent.core.lesson.LessonChatController] posts these directly via
 * `cardSpec`, bypassing [RenderCardTool] and this allowlist entirely — see that class for why).
 * Not offered to a builder agent's [com.aiopenpocket.agent.core.agent.AgentDefinition.allowedWidgetTemplates]
 * by default.
 */
val LESSON_CARD_TEMPLATES = setOf(
  "lesson_quiz", "lesson_complete", "reveal", "concept", "example", "fill", "sort", "match", "scenario",
)

/** Every template this tool recognizes at all — the schema `enum` and the "unknown template" guard below. */
private val CARD_TEMPLATES = (GENERAL_CARD_TEMPLATES + LESSON_CARD_TEMPLATES).toList()

@Inject
@SingleIn(AppScope::class)
class RenderCardTool(
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  override val name = "render_card"
  override val description =
    "Отображает интерактивную карточку в чате. Доступные шаблоны: ${CARD_TEMPLATES.joinToString(", ")}. " +
    "notification_permission — карточка запроса разрешения на уведомления, params необязательны: {title, description, button_label}. " +
    "reminder — живая карточка напоминания/heartbeat, params: {heartbeat_id} (id из ответа тула heartbeat)."
  override val schema = toolSchema(name, description) {
    string("template", "ID шаблона карточки", required = true, enum = CARD_TEMPLATES)
    // params optional: some cards (e.g. notification_permission) take none, and execute tolerates
    // their absence. Marking it required would make the central schema check reject valid calls.
    obj("params", "Параметры шаблона (зависят от template)", required = false)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val template = arguments.string("template") ?: return missingArg("template")
    // Runtime widget allowlist (Task 7, user-agent-builder-848153): the schema's `enum` above only
    // guides a well-behaved model - it is NOT the security boundary, since a hallucinated or
    // adversarial tool call can still reach this code with any string. A bound agent's own
    // AgentDefinition.allowedWidgetTemplates is re-checked here, IN CODE, before any card JSON is
    // built, so a disallowed template can never reach the chat. Plain chat (no bound agent) is
    // intentionally exempt from both checks below - kept byte-for-byte the same as before this
    // task, the same rule MemoryTools/FileTools follow for their own per-agent scoping.
    val agent = turnContextHolder.agent
    if (agent != null) {
      if (template !in CARD_TEMPLATES) {
        return ToolResult.Error("Unknown card template: '$template'.", recoverable = false)
      }
      if (template !in agent.allowedWidgetTemplates) {
        return ToolResult.Error(
          "Template '$template' is not allowed for agent '${agent.id}'.",
          recoverable = false,
        )
      }
    }
    val params = arguments["params"].toString()
    return ToolResult.Success("""{"template":"$template","params":$params}""")
  }
}
