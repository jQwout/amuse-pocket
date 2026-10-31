package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153
// @trace driven-ui-cards-0418d0

import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.loop.AgentMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The hidden built-in agent that builds other agents in a chat (owner's decision, 31 Oct 2026: the
 * six-step form scared people off; a model that asks a few short questions is the friendlier door, and it
 * is also Meta's model doing real work in the product).
 *
 * "Hidden" means: it is NOT in [MiniAgentCatalog.all], so the roster, the pickers and every list built
 * from the catalog never show it. [MiniAgentCatalog.get] still returns it, which is what makes
 * `AgentResolver.resolve(ID)` work for a chat bound to it, gives it the preset's display twins and keeps a
 * user agent from taking its id (`preset_id_collision`).
 *
 * What it can do is exactly two tools: `agent_draft` (write the draft, never publish) and `render_card`
 * (limited to [WIDGET_TEMPLATES]). Safety is in those two places, not in this prompt — the prompt only
 * says how to be useful.
 */
object BuilderAgent {

  const val ID = "agent-builder"

  /** Tool the Builder writes the draft with — see `AgentDraftTool`. */
  const val DRAFT_TOOL = "agent_draft"

  /** The only cards the Builder may render: one question per card, ticked or single. */
  val WIDGET_TEMPLATES: Set<String> = setOf("multi_choice", "choice")

  /**
   * Tools that exist but should not be suggested to a non-technical person: `external_files` currently
   * always refuses (no folder picker), `database_query` is raw SQL, `load_skill` is useless without a
   * skill and the chat cannot author skills. They stay ALLOWED for the draft (the validator accepts them,
   * the review screen shows them under "Advanced"); the prompt just does not offer them.
   */
  val ADVANCED_TOOLS: Set<String> = setOf("external_files", "database_query", "load_skill")

  /**
   * One line per tool: what it lets the agent do, in plain words (ru, en). The tool NAMES in the prompt
   * come from [AgentValidator.KNOWN_TOOL_NAMES], so a new tool shows up in the prompt by itself; the
   * test `BuilderAgentTest` fails if one is added there without a line here.
   */
  internal val TOOL_PURPOSES: Map<String, Pair<String, String>> = mapOf(
    "memory_tool" to ("помнит факты о человеке между разговорами" to "remembers facts about the person between chats"),
    "storage_tool" to ("ведёт свои заметки и счётчики" to "keeps its own notes and counters"),
    "file_tool" to ("создаёт и читает свои файлы" to "creates and reads its own files"),
    "external_files" to ("файлы вне приложения (пока всегда отказывает)" to "files outside the app (currently always refuses)"),
    "render_card" to ("показывает карточки в чате: списки, таблицы, графики, вопросы" to "shows cards in the chat: lists, tables, charts, questions"),
    "notifications" to ("присылает уведомления" to "sends notifications"),
    "heartbeat" to ("сам просыпается по расписанию, напоминает и проверяет в фоне" to "wakes up by itself on a schedule to remind and check in the background"),
    "work_manager" to ("планирует фоновые задачи" to "schedules background tasks"),
    "calendar" to ("читает и ставит события в календаре" to "reads and adds calendar events"),
    "alarm" to ("ставит будильники" to "sets alarms"),
    "email" to ("готовит черновик письма (сам не отправляет)" to "prepares an email draft (never sends it)"),
    "step_counter" to ("узнаёт число шагов" to "reads the step count"),
    "srs_tool" to ("учит слова по интервальным повторениям" to "teaches words with spaced repetition"),
    "load_skill" to ("подгружает готовый навык (бюджет, дневник, языки)" to "loads a ready-made skill (budget, diary, languages)"),
    "database_query" to ("читает данные приложения запросами SELECT" to "reads app data with SELECT queries"),
  )

  private val MODE_PURPOSES: List<Triple<AgentMode, String, String>> = listOf(
    Triple(AgentMode.CHAT, "обычный диалог (по умолчанию)", "ordinary conversation (the default)"),
    Triple(AgentMode.PLAN, "сначала составляет план и просит одобрить", "makes a plan first and asks for approval"),
    Triple(AgentMode.GOAL, "выполняет одобренный план шаг за шагом", "carries out an approved plan step by step"),
    Triple(AgentMode.REFLECT, "черновик, самопроверка, потом ответ: медленнее, но аккуратнее", "draft, self-check, then the answer: slower but more careful"),
  )

  /** The modes a draft may allow — the ones a person can pick in a chat (TUTOR is a lesson-internal mode). */
  val ALLOWED_MODES: List<AgentMode> = MODE_PURPOSES.map { it.first }

  /** What the draft's `model` may be: a family key, or "default" for the app's model. */
  const val MODEL_DEFAULT = "default"
  val ALLOWED_MODELS: List<String> = AgentModel.entries.map { it.key } + MODEL_DEFAULT

  private fun toolLines(en: Boolean): String = AgentValidator.KNOWN_TOOL_NAMES.sorted().joinToString("\n") { name ->
    val purpose = TOOL_PURPOSES[name]?.let { if (en) it.second else it.first }.orEmpty()
    val advanced = if (name in ADVANCED_TOOLS) (if (en) " [advanced: do not suggest]" else " [для продвинутых: не предлагай]") else ""
    "- $name: $purpose$advanced"
  }

  private fun modeLines(en: Boolean): String =
    MODE_PURPOSES.joinToString("\n") { (mode, ru, enText) -> "- ${mode.name}: ${if (en) enText else ru}" }

  private val promptEn: String
    get() = """
      You are Builder: a friendly guide who helps a person build their own AI agent inside this app.
      This is your own role - do not adapt to another assistant.

      How you work
      - The person is not technical. You build the agent FOR them by asking short questions, one at a time. Never show them raw tool ids, field names or JSON - use plain words ("Memory", "Reminders").
      - Speak the person's language (the language of their messages).
      - Your first message, asking what the agent should do, is already on the screen. Wait for the person's free-text answer; do not ask anything else before it.
      - Keep the draft with the agent_draft tool. After the first answer, call agent_draft with action "update" and set name (up to 60 characters), description (one sentence, up to 240 characters) and instructions (the new agent's system prompt). Call "update" again after every later answer, sending only what changed.
      - Ask every further question as a card: render_card with template "multi_choice" and params {"question": "...", "options": ["...", "..."], "preselected": [0], "min": 1, "max": 3}. ONE question per card. 3 options, 4 at most - never more. Tick (preselected) the options that fit what the person already told you. Keep labels short, in the person's language. After a card, stop and wait: the person's next message is the ticked labels joined by commas; map them back to the tool names and settings yourself.
      - Questions, in this order, skipping what is already clear:
        1. What the agent may use (tools): offer the 3-4 most fitting tools from the list below, the likely ones pre-ticked.
        2. How it should answer: length, format, tone.
        3. When it should act by itself: reminders or background checks (needs heartbeat and notifications), or never.
        4. Which model: Wonder (long, multi-step work), Spark (faster and cheaper), or the app's default.
      - When everything is set, call agent_draft with action "finish". If it reports problems, ask the person only about the missing parts, update, and finish again. When it succeeds, say in one or two sentences that the draft is ready. A "Review your agent" card appears under your message by itself - do not render it yourself.
      - You never publish. Publishing is the person's own decision on the review screen; say so if asked. You cannot do anything else for the person: if a request is outside building an agent, say so in one sentence.

      Writing the new agent's instructions: name its role or task first, then the output format, length and tone; describe the task, not the model's internals; write them in the language the agent will use with the person.

      Allowed values - agent_draft rejects anything else
      Tools (use these exact names in "tools"):
      ${toolLines(en = true)}
      Modes (use these exact names in "modes"):
      ${modeLines(en = true)}
      Models (use one of these in "model"): WONDER (long multi-step work), SPARK (faster and cheaper), default (the app's own model).
    """.trimIndent()

  private val promptRu: String
    get() = """
      Ты - Конструктор: дружелюбный проводник, который помогает человеку собрать собственного ИИ-агента прямо в этом приложении.
      Это твоя роль - не подстраивайся под другого ассистента.

      Как ты работаешь
      - Человек не обязан разбираться в технике. Ты собираешь агента ЗА него, задавая короткие вопросы по одному. Не показывай ему сырые id инструментов, названия полей и JSON - говори простыми словами ("Память", "Напоминания").
      - Говори на языке человека (на языке его сообщений).
      - Первое твоё сообщение с вопросом, что агент должен делать, уже на экране. Дождись ответа свободным текстом; до него ничего больше не спрашивай.
      - Черновик веди инструментом agent_draft. После первого ответа вызови agent_draft с action "update" и задай name (до 60 символов), description (одно предложение, до 240 символов) и instructions (системный промпт нового агента). После каждого следующего ответа снова вызывай "update", передавая только изменившееся.
      - Каждый следующий вопрос задавай карточкой: render_card с template "multi_choice" и params {"question": "...", "options": ["...", "..."], "preselected": [0], "min": 1, "max": 3}. ОДИН вопрос на карточку. 3 варианта, максимум 4 - никогда больше. Отметь заранее (preselected) варианты, подходящие к тому, что человек уже рассказал. Подписи короткие, на языке человека. После карточки остановись и жди: следующее сообщение человека - отмеченные подписи через запятую; сопоставь их с именами инструментов и настройками сам.
      - Вопросы в таком порядке, пропуская то, что уже ясно:
        1. Что агенту можно использовать (инструменты): предложи 3-4 самых подходящих из списка ниже, вероятные отметь заранее.
        2. Как отвечать: длина, формат, тон.
        3. Когда действовать самому: напоминания или фоновые проверки (нужны heartbeat и notifications) или никогда.
        4. Какая модель: Wonder (долгая многошаговая работа), Spark (быстрее и дешевле) или модель приложения по умолчанию.
      - Когда всё задано, вызови agent_draft с action "finish". Если он сообщает о проблемах, спроси человека только о недостающем, обнови и снова вызови finish. Когда получится, скажи в одном-двух предложениях, что черновик готов. Карточка "Проверь своего агента" появится под твоим сообщением сама - не рисуй её сам.
      - Ты никогда не публикуешь. Публикация - решение самого человека на экране проверки; скажи об этом, если спросят. Больше ничем ты человеку не помогаешь: если просьба не про сборку агента, скажи об этом одним предложением.

      Как писать инструкции для нового агента: сначала его роль или задача, затем формат ответа, длина и тон; описывай задачу, а не устройство модели; пиши на языке, на котором агент будет говорить с человеком.

      Допустимые значения - agent_draft отклоняет всё остальное
      Инструменты (в "tools" - ровно эти имена):
      ${toolLines(en = false)}
      Режимы (в "modes" - ровно эти имена):
      ${modeLines(en = false)}
      Модели (в "model" - одно из): WONDER (долгая многошаговая работа), SPARK (быстрее и дешевле), default (модель самого приложения).
    """.trimIndent()

  /** `template` of the card a successful `agent_draft` finish produces. Not a `render_card` template: see [reviewCardResult]. */
  const val REVIEW_TEMPLATE = "agent_review"

  /**
   * The result of a successful `agent_draft` finish. It doubles as the card spec: it has the
   * `template`/`params` shape of a `render_card` result, so the chat's card parser reads it as it is (and
   * ignores the extra keys, which are for the model). A tool result rather than a model-rendered card
   * on purpose: the card then exists exactly when the draft passed validation, and its `agent_id` is the
   * real draft of this conversation — a model cannot render a review card for an id it made up.
   */
  fun reviewCardResult(draft: AgentDefinition): String = buildJsonObject {
    put("template", REVIEW_TEMPLATE)
    put(
      "params",
      buildJsonObject {
        put("agent_id", draft.id)
        put("name", draft.name)
      },
    )
    put("status", "ready")
    put(
      "next",
      "The draft passed every check. Tell the person in one or two sentences that it is ready. " +
        "The 'Review your agent' card is shown under your message automatically - do not render it yourself. " +
        "You can't publish: the person does that on the review screen.",
    )
  }.toString()

  /** Whether a tool result [data] is the review card a successful `agent_draft` finish produced. */
  fun isReviewCard(data: String): Boolean = runCatching {
    Json.parseToJsonElement(data).jsonObject["template"]?.jsonPrimitive?.contentOrNull == REVIEW_TEMPLATE
  }.getOrDefault(false)

  val agent: MiniAgent by lazy {
    MiniAgent(
      id = ID,
      name = "Конструктор агентов",
      description = "Помогает собрать своего агента вопросами.",
      nameEn = "Agent builder",
      descriptionEn = "Helps you build your own agent by asking questions.",
      iconKey = "build",
      toolNames = setOf(DRAFT_TOOL, "render_card"),
      systemPrompt = promptRu,
      systemPromptEn = promptEn,
      allowedModes = setOf(AgentMode.CHAT),
      allowedWidgetTemplates = WIDGET_TEMPLATES,
    )
  }
}
