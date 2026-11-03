package com.aiopenpocket.agent.core.agent

// @trace builder-passport-7d7794

import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The agent a draft will become, in plain words: what the builder chat shows above the conversation while
 * the person answers, and in full on its last card (owner, 3 Nov 2026: "the follow-up questions don't
 * paint the whole picture").
 *
 * Every line is read from the stored [AgentDefinition], never from the model's retelling: the passport
 * shows what publishing would actually make, including the defaults nobody asked about ("remembers nothing
 * between chats"). A line is [PassportRow.filled] once the draft holds a real answer for it; the empty
 * ones are what the builder still has to ask.
 */
object AgentPassport {

  data class PassportRow(val label: String, val value: String, val filled: Boolean)

  /** Tools that let an agent act on its own, without a message from the person first. */
  private val PROACTIVE_TOOLS = setOf("heartbeat", "notifications", "work_manager", "alarm")

  fun rows(agent: AgentDefinition): List<PassportRow> = listOf(
    PassportRow(
      label = tr("Что делает", "What it does"),
      value = agent.description.ifBlank { tr("уточню после первого ответа", "after your first answer") },
      filled = agent.description.isNotBlank(),
    ),
    PassportRow(
      label = tr("Умеет", "Can use"),
      value = agent.toolNames.sorted().map(CapabilityNames::tool).joinToString(", ")
        .ifBlank { tr("пока только разговаривать", "only talking so far") },
      filled = agent.toolNames.isNotEmpty(),
    ),
    PassportRow(
      label = tr("Пишет первым", "Writes first"),
      value = when {
        "heartbeat" in agent.toolNames ->
          tr("да: сам просыпается по расписанию, напоминает и проверяет", "yes: wakes up on a schedule to remind and check in")
        agent.toolNames.any { it in PROACTIVE_TOOLS } ->
          tr("только напоминания, о которых ты попросишь", "only the reminders you ask for")
        else -> tr("нет, только отвечает тебе", "no, it only answers you")
      },
      filled = true,
    ),
    PassportRow(label = tr("Помнит", "Remembers"), value = memoryLine(agent), filled = true),
    PassportRow(label = tr("Спросит разрешения", "Asks first"), value = permissionLine(agent), filled = true),
    PassportRow(label = tr("Как думает", "How it thinks"), value = thinkingLine(agent.allowedModes), filled = true),
    PassportRow(
      label = tr("Модель", "Model"),
      value = when (agent.model?.let { key -> AgentModel.entries.firstOrNull { it.key == key } }) {
        AgentModel.WONDER -> tr("Muse Wonder: долгие задачи в несколько шагов", "Muse Wonder: long multi-step work")
        AgentModel.SPARK -> tr("Muse Spark: быстрее и дешевле", "Muse Spark: faster and cheaper")
        null -> tr("выбирает приложение", "the app's choice")
      },
      filled = true,
    ),
  )

  /** Tools the person must approve before every call, from the draft's BEFORE_TOOL approval rules. */
  fun askBeforeTools(agent: AgentDefinition): Set<String> = agent.hooks
    .filter { it.enabled && it.event == HookEvent.BEFORE_TOOL && it.actionType == HookActionType.REQUIRE_APPROVAL }
    .flatMap { hook ->
      when (hook.conditionType) {
        "always" -> listOf(ANY_TOOL)
        "tool_name_in" -> runCatching { Json.decodeFromString<ToolNames>(hook.conditionJson).names }.getOrDefault(emptySet())
        else -> emptyList()
      }
    }
    .toSet()

  private fun memoryLine(agent: AgentDefinition): String {
    val parts = buildList {
      if ("memory_tool" in agent.toolNames) add(tr("факты о тебе между чатами", "facts about you between chats"))
      if ("storage_tool" in agent.toolNames) add(tr("свои заметки и счётчики", "its own notes and counters"))
      if (agent.memoryPolicy.includeSharedProfile) add(tr("твой общий профиль", "your shared profile"))
    }
    return parts.joinToString(", ").ifBlank { tr("ничего между чатами, только текущий разговор", "nothing between chats, only this conversation") }
  }

  private fun permissionLine(agent: AgentDefinition): String {
    val always = tr("удаление и отправку наружу — всегда", "deleting and sending things out — always")
    val asked = askBeforeTools(agent)
    return when {
      ANY_TOOL in asked -> tr("перед каждым действием", "before every action")
      asked.isEmpty() -> always
      else -> asked.sorted().joinToString(", ", postfix = "; ") { CapabilityNames.tool(it) } + always
    }
  }

  private fun thinkingLine(modes: Set<AgentMode>): String = buildList {
    add(tr("отвечает сразу", "answers right away"))
    if (AgentMode.PLAN in modes || AgentMode.GOAL in modes) {
      add(tr("большое дело — сначала план на твоё одобрение", "a big task gets a plan for your approval first"))
    }
    if (AgentMode.REFLECT in modes) add(tr("может перепроверить себя перед ответом", "can double-check itself before answering"))
  }.joinToString("; ")

  /** `ask_before` value meaning "every tool": stored as one `always` approval rule. */
  const val ANY_TOOL = "any"

  @Serializable
  private data class ToolNames(val names: Set<String> = emptySet())
}
