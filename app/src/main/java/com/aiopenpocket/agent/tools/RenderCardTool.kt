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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * General-purpose templates any agent (preset or user-built) may render — not tied to the AI
 * Academy lesson flow. This is the subset a builder-created agent is expected to be scoped to by
 * default (see [com.aiopenpocket.agent.core.agent.AgentDefinition.allowedWidgetTemplates]), though
 * [RenderCardTool] itself does not hardcode that default — it only enforces whichever allowlist a
 * bound agent was actually given.
 */
val GENERAL_CARD_TEMPLATES = setOf(
  "todo_list", "timer", "chart_bar", "table", "form", "choice", "multi_choice",
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
    "reminder — живая карточка напоминания/heartbeat, params: {heartbeat_id} (id из ответа тула heartbeat). " +
    "multi_choice — вопрос с несколькими отмечаемыми вариантами и кнопкой «Далее», params: " +
    "{question, options (2-4 коротких подписи), preselected (индексы заранее отмеченных), min, max}; " +
    "ответ человека придёт его следующим сообщением — отмеченные подписи через запятую."
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
    if (template == MULTI_CHOICE) {
      multiChoiceProblem(arguments["params"])?.let { return ToolResult.Error(it) }
    }
    val params = arguments["params"].toString()
    return ToolResult.Success("""{"template":"$template","params":$params}""")
  }
}

private const val MULTI_CHOICE = "multi_choice"

/** The most options one `multi_choice` card may carry: a longer list is a wall of choices, not a question. */
private const val MULTI_CHOICE_MAX_OPTIONS = 4

/**
 * Why a `multi_choice` call cannot be drawn, or null when it can. Checked here, in the tool, so the model
 * gets the reason back and fixes the call - the parser in the chat repairs what it can, but a card with
 * one option, or a dozen, is a bad question rather than a broken spec. Owner's rule for the agent builder:
 * 2-4 options, one question per card.
 */
internal fun multiChoiceProblem(params: JsonElement?): String? {
  val obj = params as? JsonObject ?: return "multi_choice needs params {question, options}."
  val question = (obj["question"] as? JsonPrimitive)?.content.orEmpty()
  if (question.isBlank()) return "multi_choice needs a non-empty 'question'."
  val options = (obj["options"] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { label -> label.isNotBlank() } }
    ?: return "multi_choice needs 'options': a list of 2-$MULTI_CHOICE_MAX_OPTIONS short labels."
  if (options.size !in 2..MULTI_CHOICE_MAX_OPTIONS) {
    return "multi_choice takes 2-$MULTI_CHOICE_MAX_OPTIONS options, got ${options.size}. Ask one question per card."
  }
  return null
}
