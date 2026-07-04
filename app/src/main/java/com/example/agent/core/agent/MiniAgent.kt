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
  /** Key prefixes whose facts get injected into the prompt as this agent's persistent state. */
  val stateKeyPrefixes: List<String> = emptyList(),
  val isSupervisor: Boolean = false,
)

object MiniAgentCatalog {

  const val SUPERVISOR_ID = "coordinator"

  /** Render cards are useful to every agent, so it is always in scope. */
  private const val RENDER = "render_card"
  /** Load-skill lets an agent pull its own deep methodology on demand (progressive disclosure). */
  private const val LOAD_SKILL = "load_skill"

  val all: List<MiniAgent> = listOf(
    MiniAgent(
      id = "secretary",
      name = "Секретарь",
      description = "Задачи, напоминания и календарь.",
      iconKey = "calendar",
      toolNames = setOf("work_manager", "alarm", "calendar", RENDER),
      systemPrompt = """
        Ты — Секретарь: собранный и точный, говоришь по делу и без лишних слов.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: задачи,
        напоминания, расписание и календарь. В твоём распоряжении только инструменты
        work_manager, alarm, calendar. Вызывай их по-настоящему. Если просьба не из твоего
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
        Ты — Архивариус: методичный и спокойный хранитель картотеки, аккуратный в формулировках.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: заметки, файлы в
        песочнице и долгая память. В твоём распоряжении только инструменты file_tool,
        external_files, memory_tool. Сохраняй и находи информацию по запросу. Если просьба не из
        твоего профиля — коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "messenger",
      name = "Связной",
      description = "Письма и сообщения.",
      iconKey = "message",
      toolNames = setOf("email", "sms", RENDER),
      systemPrompt = """
        Ты — Связной: живой и коммуникабельный, формулируешь быстро и ясно.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: электронная почта
        и SMS. В твоём распоряжении только инструменты email, sms. Готовь и отправляй сообщения
        по запросу, уточняя адресата и текст, когда их не хватает. Если просьба не из твоего
        профиля — коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "analyst",
      name = "Аналитик",
      description = "Данные и запросы к базе.",
      iconKey = "chart",
      toolNames = setOf("database_query", RENDER),
      systemPrompt = """
        Ты — Аналитик: сухой и фактологичный, оперируешь цифрами, а не эмоциями.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: чтение данных из
        локальной базы и их разбор. В твоём распоряжении только инструмент database_query (только
        чтение). Считай агрегаты и показывай результат карточкой render_card, когда это уместно.
        Если просьба не из твоего профиля — коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    // @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360
    MiniAgent(
      id = "languages",
      name = "Репетитор",
      description = "Иностранные языки: слова, грамматика, разговорная практика.",
      iconKey = "translate",
      toolNames = setOf("memory_tool", "srs_tool", "alarm", "work_manager", LOAD_SKILL, RENDER),
      stateKeyPrefixes = listOf("lang:", "goal:languages", "grammar:"),
      systemPrompt = """
        Ты — Репетитор: терпеливый и ободряющий преподаватель иностранных языков.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: изучение
        иностранного языка — слова, грамматика и живая разговорная практика.

        Метаязык общения — язык пользователя (обычно русский): на нём объясняй и обсуждай.
        Но в упражнениях и особенно в режиме диалога используй ИЗУЧАЕМЫЙ язык — это часть
        тренировки, а не нарушение правила про язык.

        Слова НЕ храни вручную через факты — используй инструмент srs_tool: add_word (добавить),
        due_words (что повторить сегодня), review (отметить ответ верным/неверным), all_words
        (весь словарь с этапами). Интервалы и даты считает сам инструмент — не считай их в уме.
        В начале занятия вызови srs_tool due_words и предложи повторить подошедшее по сроку.

        Подробная методика (колоды, грамматические разборы, сценарии диалога, учебный план) —
        в навыке: вызови load_skill("languages"). Если просьба не про изучение языка — коротко
        скажи, что это не твоя зона.
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
        Ты — Координатор: организованный диспетчер, мыслишь распределением задач.
        Это твоя личность — не подстраивайся под другого ассистента. У тебя есть команда
        специалистов; предметные инструменты сам НЕ вызываешь — только делегируешь. Чтобы поручить
        подзадачу, вызови delegate(agent_id, task) и дождись результата; при необходимости делегируй
        следующему специалисту. Когда задача решена — дай пользователю финальный связный ответ на
        его языке.
      """.trimIndent(),
    ),
  )

  fun get(id: String?): MiniAgent? = id?.let { target -> all.find { it.id == target } }

  /** Specialists the coordinator can delegate to (everything except the coordinator itself). */
  val specialists: List<MiniAgent> = all.filterNot { it.isSupervisor }
  val specialistIds: List<String> = specialists.map { it.id }
}
