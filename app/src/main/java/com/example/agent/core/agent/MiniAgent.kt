package com.aiopenpocket.agent.core.agent

/**
 * A specialist "mini-agent" — the ai-open-pocket take on openQwoutt's mini-apps, reframed as agents.
 * Each one is an identity ([name]/[description]/[iconKey]) bound to a scoped tool set ([toolNames])
 * and its own [systemPrompt]. A chat bound to a mini-agent runs with only that agent's tools and role.
 *
 * The [isSupervisor] agent owns no domain tools; it coordinates the others via a `delegate` tool and
 * is routed through SupervisorOrchestrator instead of the plain chat loop.
 */
data class MiniAgent(
  val id: String,
  val name: String,
  val description: String,
  val iconKey: String,
  val toolNames: Set<String>,
  val systemPrompt: String,
  val isSupervisor: Boolean = false,
)

object MiniAgentCatalog {

  const val SUPERVISOR_ID = "coordinator"

  /** Render cards are useful to every agent, so it is always in scope. */
  private const val RENDER = "render_card"

  val all: List<MiniAgent> = listOf(
    MiniAgent(
      id = "secretary",
      name = "Секретарь",
      description = "Задачи, напоминания и календарь.",
      iconKey = "calendar",
      toolNames = setOf("work_manager", "alarm", "calendar", RENDER),
      systemPrompt = """
        Ты — агент-секретарь. Профиль: задачи, напоминания, расписание и календарь.
        В твоём распоряжении только инструменты work_manager, alarm, calendar.
        Решай задачу по делу и вызывай инструменты по-настоящему. Если просьба не из твоего
        профиля — коротко скажи, что это не твоя зона, и предложи передать другому агенту.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "archivist",
      name = "Архивариус",
      description = "Заметки, файлы и память.",
      iconKey = "folder",
      toolNames = setOf("file_tool", "external_files", "memory_tool", RENDER),
      systemPrompt = """
        Ты — агент-архивариус. Профиль: заметки, файлы в песочнице и долгая память.
        В твоём распоряжении только инструменты file_tool, external_files, memory_tool.
        Сохраняй и находи информацию по запросу. Если просьба не из твоего профиля — коротко
        скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "messenger",
      name = "Связной",
      description = "Письма и сообщения.",
      iconKey = "message",
      toolNames = setOf("email", "sms", RENDER),
      systemPrompt = """
        Ты — агент-связной. Профиль: электронная почта и SMS.
        В твоём распоряжении только инструменты email, sms. Готовь и отправляй сообщения по запросу,
        уточняя адресата и текст, когда их не хватает. Если просьба не из твоего профиля — коротко
        скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "analyst",
      name = "Аналитик",
      description = "Данные и запросы к базе.",
      iconKey = "chart",
      toolNames = setOf("database_query", RENDER),
      systemPrompt = """
        Ты — агент-аналитик. Профиль: чтение данных из локальной базы и их разбор.
        В твоём распоряжении только инструмент database_query (только чтение). Считай агрегаты и
        показывай результат карточкой render_card, когда это уместно. Если просьба не из твоего
        профиля — коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = SUPERVISOR_ID,
      name = "Координатор",
      description = "Распределяет задачу между агентами-специалистами.",
      iconKey = "tree",
      toolNames = emptySet(),
      isSupervisor = true,
      systemPrompt = """
        Ты — агент-координатор. У тебя есть команда специалистов; предметные инструменты сам НЕ
        вызываешь — только делегируешь. Чтобы поручить подзадачу, вызови delegate(agent_id, task)
        и дождись результата; при необходимости делегируй следующему специалисту. Когда задача
        решена — дай пользователю финальный связный ответ на его языке.
      """.trimIndent(),
    ),
  )

  fun get(id: String?): MiniAgent? = id?.let { target -> all.find { it.id == target } }

  /** Specialists the coordinator can delegate to (everything except the coordinator itself). */
  val specialists: List<MiniAgent> = all.filterNot { it.isSupervisor }
  val specialistIds: List<String> = specialists.map { it.id }
}
