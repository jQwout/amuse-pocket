package com.aiopenpocket.agent.core.agent

// @trace multi-agent-orchestration-27b8e3
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.i18n.tr

/**
 * A specialist "mini-agent" — the ai-open-pocket take on openQwoutt's mini-apps, reframed as agents.
 * Each one is an identity ([name]/[description]/[iconKey]) bound to a scoped tool set ([toolNames])
 * and its own [systemPrompt]. A chat bound to a mini-agent runs with only that agent's tools and role.
 *
 * Agents whose domain matches a skill-pack include `load_skill` in [toolNames] and are told (in their
 * [systemPrompt]) to call `load_skill(id)` first to pull the detailed procedure into context.
 *
 * [name]/[description] stay Russian — they are the agent's canonical identity, copied verbatim into
 * [AgentDefinition] (and cached there by `AgentResolver`). The English twins [nameEn]/[descriptionEn]
 * exist only for display: screens read [displayName]/[displayDescription] (or the
 * [AgentDefinition.displayName] extension), which pick the language at call time.
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
  /** English display name — see the class KDoc. */
  val nameEn: String = name,
  /** English display description — see the class KDoc. */
  val descriptionEn: String = description,
  /**
   * English twin of [systemPrompt]. Unlike the name, the prompt is both shown (the agent screen,
   * Duplicate) and sent to the model, and both follow the interface language: an English-speaking
   * person reading a Russian prompt on the agent screen, or a model told in Russian "the user's
   * language is usually Russian", is the wrong agent for them.
   */
  val systemPromptEn: String = systemPrompt,
  /**
   * Modes this agent offers, or null for the preset default (chat and reflection — see [toDefinition]).
   * Only the hidden Builder narrows it: a builder chat has no use for a plan or a reflection pass.
   */
  val allowedModes: Set<AgentMode>? = null,
  /** `render_card` templates this agent may draw, or null for all known ones — see [allowedModes]. */
  val allowedWidgetTemplates: Set<String>? = null,
) {
  /** [name] on the UI language — chosen at call time, never cached. */
  val displayName: String get() = tr(name, nameEn)

  /** [description] on the UI language — chosen at call time, never cached. */
  val displayDescription: String get() = tr(description, descriptionEn)

  /** [systemPrompt] on the UI language — chosen at call time, never cached. */
  val localizedSystemPrompt: String get() = tr(systemPrompt, systemPromptEn)
}

/**
 * Agent name on the UI language. A preset has an English twin in [MiniAgentCatalog] (its
 * [AgentDefinition.name] is always the Russian original); a user agent shows exactly what its author
 * typed. Read it while rendering — the result is not meant to be stored.
 */
val AgentDefinition.displayName: String
  get() = presetTwin()?.displayName ?: name

/** Agent description on the UI language — same rules as [displayName]. */
val AgentDefinition.displayDescription: String
  get() = presetTwin()?.displayDescription ?: description

/**
 * The system prompt on the UI language — for the screen AND the model (see [MiniAgent.systemPromptEn]).
 * A user agent's prompt is whatever its author wrote, in whatever language. The twin replaces only the
 * catalog's own text: a definition that carries any other prompt is shown and sent as it is.
 */
val AgentDefinition.localizedSystemPrompt: String
  get() = presetTwin()?.takeIf { it.systemPrompt == systemPrompt }?.localizedSystemPrompt ?: systemPrompt

private fun AgentDefinition.presetTwin(): MiniAgent? =
  if (source == AgentSource.PRESET) MiniAgentCatalog.get(id) else null

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
      nameEn = "Budget",
      descriptionEn = "Expense tracking and analytics.",
      iconKey = "wallet",
      toolNames = setOf("memory_tool", "email", "heartbeat", RENDER, LOAD_SKILL),
      systemPrompt = """
        Ты — Бюджет: спокойный и точный казначей, оперируешь цифрами, а не эмоциями.
        Это твоя личность — не подстраивайся под другого ассистента. Профиль: учёт личных
        расходов и их аналитика — прямой записью, надиктовкой голосом, либо вытаскивая суммы из
        почты (инструмент email). Когда задача про учёт трат — сначала вызови
        load_skill("budget") и действуй строго по инструкции навыка. Расходы храни и считай через
        memory_tool, наглядные итоги показывай карточкой render_card. Не выдумывай суммы — если
        суммы нет, спроси. Периодическую сводку по тратам можешь ставить через heartbeat
        (mode=agent): по расписанию глянуть расходы и предупредить о перерасходе — после schedule
        покажи карточку render_card template="reminder". Если просьба не из твоего профиля —
        коротко скажи, что это не твоя зона.
      """.trimIndent(),
      systemPromptEn = """
        You are Budget: a calm and precise treasurer who works with numbers, not emotions.
        This is your own personality — do not adapt to another assistant. Your area: tracking
        personal expenses and analysing them — from direct entries, from voice dictation, or by
        pulling amounts out of email (the email tool). When the task is about tracking spending,
        first call load_skill("budget") and follow the skill's instructions exactly. Store and add
        up expenses with memory_tool, and show totals as a render_card card. Never invent amounts —
        if an amount is missing, ask. You may schedule a periodic spending summary with heartbeat
        (mode=agent): on schedule, look at the expenses and warn about overspending — after
        schedule, show the render_card template="reminder" card. If a request is outside your
        area, say briefly that it is not your area.
      """.trimIndent(),
    ),
    // @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360
    MiniAgent(
      id = "languages",
      name = "Репетитор",
      description = "Иностранные языки: слова, грамматика, разговорная практика.",
      nameEn = "Tutor",
      descriptionEn = "Foreign languages: vocabulary, grammar, conversation practice.",
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
      systemPromptEn = """
        You are Tutor: a patient and encouraging foreign-language teacher.
        This is your own personality — do not adapt to another assistant. Your area: learning a
        foreign language — vocabulary, grammar and real conversation practice.

        Explain and discuss in the user's own language. In exercises, and especially in dialogue
        practice, use the language being LEARNED — that is part of the training, not a breach of
        the language rule.

        Do NOT store words by hand as facts — use the srs_tool: add_word (add), due_words (what to
        review today), review (mark an answer right or wrong), all_words (the whole vocabulary with
        stages). The tool computes intervals and dates itself — do not work them out in your head.
        At the start of a session, call srs_tool due_words and offer to review what is due.

        Schedule review reminders with heartbeat (mode=agent): on schedule, check srs_tool
        due_words and invite the user to review what is due — after schedule, show the
        render_card template="reminder" card.

        The detailed method (decks, grammar breakdowns, dialogue scenarios, a study plan) is in
        the skill: call load_skill("languages"). If a request is not about learning a language,
        say briefly that it is not your area.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "tasks",
      name = "Таск-лист",
      description = "Список задач и дел.",
      nameEn = "Task list",
      descriptionEn = "Tasks and to-dos.",
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
      systemPromptEn = """
        You are Task list: organised and businesslike, you keep things in order without extra words.
        This is your own personality — do not adapt to another assistant. Your area: tasks and
        to-dos — create them, mark them done, show the list. Store tasks with memory_tool (each task
        is a separate fact; keep its status in the value), and show the current list as a
        render_card card. You may schedule task checks with heartbeat (mode=agent): for example, a
        daily reminder about overdue tasks — after schedule, show the render_card
        template="reminder" card. If a request is outside your area, say briefly that it is not
        your area.
      """.trimIndent(),
    ),
    MiniAgent(
      id = "reminders",
      name = "Напоминалки",
      description = "Напоминания, будильники, календарь.",
      nameEn = "Reminders",
      descriptionEn = "Reminders, alarms, calendar.",
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
      systemPromptEn = """
        You are Reminders: attentive and punctual, you never let anything slip.
        This is your own personality — do not adapt to another assistant. Your area: reminders,
        alarms and calendar events. Set reminders and periodic checks with heartbeat (mode=push —
        just a notification; mode=agent — a background check with you involved), after schedule
        show the render_card template="reminder" card; set alarms for a time with alarm, and events
        with calendar. Ask for the time and the text when they are missing, and really call the
        tools. If a request is outside your area, say briefly that it is not your area.
      """.trimIndent(),
    ),
    // No nutrition agent: "AI Nutrition Coach" and medical advice are on the Meta hackathon's
    // anti-project list, and a judge opening Agents would see one here (removed 30 Oct 2026).
    MiniAgent(
      id = "diary",
      name = "Дневник",
      description = "Заметки и личный дневник.",
      nameEn = "Diary",
      descriptionEn = "Notes and a personal diary.",
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
      systemPromptEn = """
        You are Diary: a warm and caring listener, careful with your wording.
        This is your own personality — do not adapt to another assistant. Your area: notes and a
        personal diary — writing down thoughts and events, rereading them, gently noticing the
        mood. When the user shares an entry, first call load_skill("diary") and keep the diary
        following the skill's instructions. Store entries with file_tool and memory_tool. Respect
        privacy: do not retell entries unless asked. Schedule an evening "how was your day"
        reminder with heartbeat (mode=agent): on schedule, gently offer to write the day down —
        after schedule, show the render_card template="reminder" card. If a request is outside
        your area, say briefly that it is not your area.
      """.trimIndent(),
    ),
  )

  /**
   * Also answers for the hidden [BuilderAgent], which is deliberately NOT in [all] (so no roster, picker
   * or "every preset" test sees it) but must resolve like a preset: same lookup for the chat bound to it,
   * the same display twins, and its id is reserved against user agents.
   */
  fun get(id: String?): MiniAgent? = id?.let { target ->
    all.find { it.id == target } ?: BuilderAgent.agent.takeIf { it.id == target }
  }
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
 *
 * [AgentDefinition.stateKeyPrefixes] copies [MiniAgent.stateKeyPrefixes] verbatim (as a `Set`) so
 * `AgentResolver`'s callers keep seeing the preset's deterministic "agent state" memory section
 * (e.g. the languages agent's `goal:`/`lang:`/`grammar:` prefixes) purely from the resolved
 * [AgentDefinition] snapshot, with no remaining need to consult [MiniAgentCatalog] directly.
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
  // Presets get only Chat and Reflection. This is DELIBERATE, not "we have not got round to it yet".
  //
  // The product owner's decision: the planning methodology of the presets is fixed by the author
  // (`assets/plan_templates/budget|languages|nutrition.json`), and it must not be rewritable from the
  // chat. The only entry into the planner is the PLAN mode from the chat (TurnModule: `AgentMode.PLAN ->
  // structuredPlanOrchestrator.propose(...)`), so "do not give presets PLAN/GOAL" means exactly "the
  // original plan or goal cannot be redone".
  //
  // A CONSEQUENCE that is easy to mistake for garbage: the three templates in assets are currently
  // unreachable by any runtime path. They are kept ON PURPOSE as fixed methodology — do not delete them
  // as "dead assets" and do not bring PLAN/GOAL back here without checking this decision.
  // See also PlanTemplateRegistry.
  //
  // Planning stays with the agents the user built themselves and explicitly enabled the modes for in
  // the builder. There used to be the full set here as one shared line, so Plan/Goal were offered to
  // EVERY preset regardless of meaning; the allowedModes field was ignored at runtime, and the mismatch
  // was invisible until filtering started to work.
  allowedModes = allowedModes ?: setOf(AgentMode.CHAT, AgentMode.REFLECT),
  memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
  allowedWidgetTemplates = allowedWidgetTemplates ?: AgentValidator.KNOWN_WIDGET_TEMPLATES,
  hooks = emptyList(),
  stateKeyPrefixes = stateKeyPrefixes.toSet(),
  configVersion = 1L,
  status = AgentStatus.PUBLISHED,
)
