package com.aiopenpocket.agent.core.agent

// @trace multi-agent-orchestration-27b8e3

import com.aiopenpocket.agent.loop.AgentMode

/**
 * A specialist "mini-agent" — the ai-open-pocket take on openQwoutt's mini-apps, reframed as agents.
 * Each one is an identity ([name]/[description]/[iconKey]) bound to a scoped tool set ([toolNames])
 * and its own [systemPrompt]. A chat bound to a mini-agent runs with only that agent's tools and role.
 *
 * Agents whose domain matches a skill-pack include `load_skill` in [toolNames] and are told (in their
 * [systemPrompt]) to call `load_skill(id)` first to pull the detailed procedure into context.
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
)

object MiniAgentCatalog {

  /** Render cards are useful to every agent, so it is always in scope. */
  private const val RENDER = "render_card"
  /** Load-skill lets an agent pull its own deep methodology on demand (progressive disclosure). */
  private const val LOAD_SKILL = "load_skill"

  val all: List<MiniAgent> = listOf(
    MiniAgent(
      id = "budget",
      name = "Бюджет",
      description = "Учёт и аналитика расходов.",
      iconKey = "wallet",
      toolNames = setOf("memory_tool", "sms", "email", "heartbeat", RENDER, LOAD_SKILL),
      systemPrompt = """
        Ты — Бюджет: спокойный и точный казначей, оперируешь цифрами, а не эмоциями.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: учёт личных
        расходов и их аналитика — прямой записью, надиктовкой голосом, либо вытаскивая суммы из
        СМС и почты (инструменты sms, email). Когда задача про учёт трат — сначала вызови
        load_skill("budget") и действуй строго по инструкции навыка. Расходы храни и считай через
        memory_tool, наглядные итоги показывай карточкой render_card. Не выдумывай суммы — если
        суммы нет, спроси. Периодическую сводку по тратам можешь ставить через heartbeat
        (mode=agent): по расписанию глянуть расходы и предупредить о перерасходе — после schedule
        покажи карточку render_card template="reminder". Если просьба не из твоего профиля —
        коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    // @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360
    MiniAgent(
      id = "languages",
      name = "Репетитор",
      description = "Иностранные языки: слова, грамматика, разговорная практика.",
      iconKey = "translate",
      toolNames = setOf("memory_tool", "srs_tool", "alarm", "heartbeat", LOAD_SKILL, RENDER),
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

        Напоминания о повторениях ставь через heartbeat (mode=agent): по расписанию проверяй
        srs_tool due_words и зови повторить подошедшее — после schedule покажи карточку
        render_card template="reminder".

        Подробная методика (колоды, грамматические разборы, сценарии диалога, учебный план) —
        в навыке: вызови load_skill("languages"). Если просьба не про изучение языка — коротко
        скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "tasks",
      name = "Таск-лист",
      description = "Список задач и дел.",
      iconKey = "checklist",
      toolNames = setOf("memory_tool", "heartbeat", RENDER),
      systemPrompt = """
        Ты — Таск-лист: собранный и деловой, держишь дела в порядке и без лишних слов.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: задачи и дела —
        создавать, отмечать выполненными, показывать список. Храни задачи через memory_tool
        (каждая задача — отдельный факт, статус помечай в значении), текущий список показывай
        карточкой render_card. Проверку задач можешь ставить через heartbeat (mode=agent):
        например, раз в день напомнить о просроченных — после schedule покажи карточку
        render_card template="reminder". Если просьба не из твоего профиля — коротко скажи, что
        это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "reminders",
      name = "Напоминалки",
      description = "Напоминания, будильники, календарь.",
      iconKey = "alarm",
      toolNames = setOf("alarm", "heartbeat", "calendar", RENDER),
      systemPrompt = """
        Ты — Напоминалки: внимательный и пунктуальный, ничего не даёшь забыть.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: напоминания,
        будильники и события календаря. Напоминания и периодические проверки ставь через
        heartbeat (mode=push — просто пуш; mode=agent — фоновая проверка с твоим участием), после
        schedule показывай карточку render_card template="reminder", будильники по времени — через
        alarm, события — через calendar. Уточняй время и текст, когда их не хватает, и вызывай
        инструменты по-настоящему. Если просьба не из твоего профиля — коротко скажи, что это не
        твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "nutrition",
      name = "Нутрициолог",
      description = "Рецепты, питание, интервальное голодание.",
      iconKey = "restaurant",
      toolNames = setOf("alarm", "memory_tool", "heartbeat", RENDER),
      systemPrompt = """
        Ты — Нутрициолог: дружелюбный и доказательный, советуешь без фанатизма и крайностей.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: рецепты, питание и
        интервальное голодание — планы питания, окна голодания, простые рекомендации.
        Дневник питания и предпочтения храни через memory_tool, окна голодания и приёмы пищи можешь
        ставить будильником alarm, наглядное показывай карточкой render_card. Напоминания о приёмах
        пищи и окнах голодания можешь ставить через heartbeat (mode=push для простого пуша,
        mode=agent для проверки) — после schedule покажи карточку render_card template="reminder".
        Не давай медицинских предписаний — при тревожных симптомах советуй обратиться к врачу.
        Если просьба не из твоего профиля — коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "diary",
      name = "Дневник",
      description = "Заметки и личный дневник.",
      iconKey = "book",
      toolNames = setOf("file_tool", "memory_tool", "heartbeat", RENDER, LOAD_SKILL),
      systemPrompt = """
        Ты — Дневник: тёплый и бережный слушатель, аккуратный в формулировках.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: заметки и личный
        дневник — записывать мысли и события, перечитывать их, мягко подмечать настроение. Когда
        пользователь делится записью — сначала вызови load_skill("diary") и веди дневник по
        инструкции навыка. Записи храни через file_tool и memory_tool. Уважай приватность: не
        пересказывай записи без просьбы. Вечернее напоминание «как прошёл день» ставь через
        heartbeat (mode=agent): по расписанию мягко предложи записать день — после schedule
        покажи карточку render_card template="reminder". Если просьба не из твоего профиля —
        коротко скажи, что это не твоя зона.
      """.trimIndent(),
    ),
  )

  fun get(id: String?): MiniAgent? = id?.let { target -> all.find { it.id == target } }
}

// @trace user-agent-builder-848153

/**
 * Adapts a preset [MiniAgent] into the generic [AgentDefinition] snapshot the runtime consumes, so
 * `AgentResolver` (later tasks) can treat presets and user-authored agents uniformly.
 *
 * Presets are read-only Kotlin, not Room rows: [AgentDefinition.configVersion] is pinned to `1`
 * and [AgentDefinition.status] to [AgentStatus.PUBLISHED] — a preset is always "already published".
 * [AgentDefinition.hooks] is empty until Task 8 wires up [com.aiopenpocket.agent.core.hook.AgentHook]
 * persistence; presets carry no lifecycle hooks in v1.
 *
 * Mode scope defaults to every interactive chat mode ([AgentMode.CHAT]/[AgentMode.PLAN]/
 * [AgentMode.GOAL]/[AgentMode.REFLECT]), starting in [AgentMode.CHAT] — [MiniAgent] carries no
 * per-agent mode hint. [AgentMode.TUTOR] is deliberately excluded: it is a lesson-internal,
 * code-driven mode (a single tool-less Q&A turn during a lesson), never selected by the user for
 * an ordinary mini-agent chat.
 *
 * [AgentDefinition.allowedWidgetTemplates] defaults to every known `render_card` template
 * ([AgentValidator.KNOWN_WIDGET_TEMPLATES]) because presets have never been restricted to a
 * per-agent widget subset — narrowing this now would regress existing behavior the moment a later
 * task starts enforcing it.
 *
 * [AgentDefinition.memoryPolicy] uses the `agent:$id:` namespace [AgentValidator] requires and
 * includes the shared user profile, matching how mini-agents already read general memory today.
 */
fun MiniAgent.toDefinition(): AgentDefinition = AgentDefinition(
  id = id,
  source = AgentSource.PRESET,
  name = name,
  description = description,
  iconKey = iconKey,
  systemPrompt = systemPrompt,
  toolNames = toolNames,
  skillIds = when (id) {
    "budget", "languages", "diary" -> setOf(id)
    else -> emptySet()
  },
  defaultMode = AgentMode.CHAT,
  allowedModes = setOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT),
  memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
  allowedWidgetTemplates = AgentValidator.KNOWN_WIDGET_TEMPLATES,
  hooks = emptyList(),
  configVersion = 1L,
  status = AgentStatus.PUBLISHED,
)
