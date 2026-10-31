package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.i18n.tr
import kotlinx.serialization.json.JsonElement

/**
 * The single source of human-readable names for tools, their actions and built-in skills. Raw ids
 * (`memory_tool`, `search_memory`, `budget`) are a contract with the model and tell the user nothing.
 * Every screen used to translate them on its own: the "Can do" chips and the builder each with its own
 * dictionary (the same thing was called "Dictionary" in one and "Dictionary (SRS)" in the other), while
 * the confirmation bar, the call log and the agent screen showed ids as is, in a monospace font.
 *
 * It lives next to [Tool], not in ui: the "Using tool: …" caption in the bubble is assembled by the
 * agent loop layer, which does not depend on ui. Pure Kotlin — tested with plain JUnit.
 *
 * Never throws and never returns an empty string: an unknown name (a tool from a newer app version,
 * someone else's skill) becomes a readable caption made from the id itself.
 *
 * Every name is stored as a "Russian — English" pair, and the language is picked at call time ([tr]),
 * not when the dictionaries are initialized: otherwise captions would freeze in the language of the
 * first access.
 */
object CapabilityNames {

  /** Tool names from [com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_TOOL_NAMES]: id → (ru, en). */
  private val TOOLS: Map<String, Pair<String, String>> = mapOf(
    "memory_tool" to ("Память" to "Memory"),
    "storage_tool" to ("Хранилище агента" to "Agent storage"),
    "file_tool" to ("Файлы агента" to "Agent files"),
    "external_files" to ("Внешние файлы" to "External files"),
    "render_card" to ("Карточки" to "Cards"),
    "notifications" to ("Уведомления" to "Notifications"),
    "heartbeat" to ("Напоминания" to "Reminders"),
    "work_manager" to ("Фоновые задачи" to "Background tasks"),
    "calendar" to ("Календарь" to "Calendar"),
    "alarm" to ("Будильник" to "Alarm"),
    "email" to ("Почта" to "Email"),
    "srs_tool" to ("Словарь" to "Vocabulary"),
    "step_counter" to ("Шагомер" to "Step counter"),
    "load_skill" to ("Навыки" to "Skills"),
    "database_query" to ("Запросы к базе" to "Database queries"),
    // The hidden agent builder's own tool - not in KNOWN_TOOL_NAMES (a user's agent cannot hold it), but
    // its calls show in the tool trace, which should not say "Agent draft" as a raw id.
    "agent_draft" to ("Черновик агента" to "Agent draft"),
  )

  /** Shared actions of the file tools (agent sandbox and external files). Declared before [ACTIONS] — initialization order. */
  private val FILE_ACTIONS: Map<String, Pair<String, String>> = mapOf(
    "read_file" to ("прочитать файл" to "read a file"),
    "write_file" to ("записать файл" to "write a file"),
    "list_dir" to ("показать папку" to "list a folder"),
  )

  /**
   * Actions inside tools — the value of the `action` argument, i.e. what the model actually asks to
   * do. Per tool, not one list: `read_file`/`list` exist in several tools at once.
   */
  private val ACTIONS: Map<String, Map<String, Pair<String, String>>> = mapOf(
    "memory_tool" to mapOf(
      "remember_fact" to ("запомнить факт" to "remember a fact"),
      "search_memory" to ("найти факты" to "search facts"),
      "recall_episodes" to ("вспомнить разговоры" to "recall conversations"),
    ),
    "storage_tool" to mapOf(
      "put" to ("сохранить запись" to "save a record"),
      "get" to ("прочитать запись" to "read a record"),
      "list" to ("показать записи" to "list records"),
      "delete" to ("удалить запись" to "delete a record"),
    ),
    "file_tool" to FILE_ACTIONS + ("search_files" to ("найти файлы" to "search files")),
    "external_files" to FILE_ACTIONS,
    "heartbeat" to mapOf(
      "schedule" to ("запланировать" to "schedule"),
      "cancel" to ("отменить" to "cancel"),
      "list" to ("показать запланированные" to "list scheduled"),
    ),
    "work_manager" to mapOf(
      "schedule_notification" to ("запланировать уведомление" to "schedule a notification"),
      "schedule_periodic" to ("запланировать повтор" to "schedule a repeat"),
      "cancel_work" to ("отменить задачу" to "cancel a task"),
    ),
    "calendar" to mapOf(
      "list_events" to ("показать события" to "list events"),
      "create_event" to ("создать событие" to "create an event"),
      "delete_event" to ("удалить событие" to "delete an event"),
    ),
    "alarm" to mapOf(
      "set_alarm" to ("завести" to "set"),
      "show_alarms" to ("показать все" to "show all"),
    ),
    "email" to mapOf("compose" to ("написать письмо" to "write a draft")),
    "srs_tool" to mapOf(
      "add_word" to ("добавить слово" to "add a word"),
      "due_words" to ("что повторить сегодня" to "words due today"),
      "review" to ("отметить повторение" to "log a review"),
      "all_words" to ("показать все слова" to "show all words"),
    ),
    "step_counter" to mapOf("snapshot" to ("узнать шаги" to "get steps")),
    "agent_draft" to mapOf(
      "get" to ("прочитать черновик" to "read the draft"),
      "update" to ("обновить черновик" to "update the draft"),
      "validate" to ("проверить черновик" to "check the draft"),
      "finish" to ("завершить черновик" to "finish the draft"),
    ),
  )

  /** Names of built-in skills ([com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_SKILL_IDS]). */
  private val SKILLS: Map<String, Pair<String, String>> = mapOf(
    "budget" to ("Бюджет" to "Budget"),
    "diary" to ("Дневник" to "Diary"),
    "fitness" to ("Фитнес" to "Fitness"),
    "languages" to ("Языки" to "Languages"),
  )

  private const val LOAD_SKILL = "load_skill"

  /** Whether a tool has its own name — the "Can do" chips should rather skip an unknown tool than label it by id. */
  fun isKnownTool(name: String): Boolean = name in TOOLS

  /**
   * A tool's short name: "Memory", "Alarm". Also understands an action name if someone passed it as a
   * function name (`search_memory` → "Memory: find facts").
   */
  fun tool(name: String): String {
    TOOLS[name]?.let { return it.localized() }
    ACTIONS.entries.firstNotNullOfOrNull { (toolName, actions) ->
      actions[name]?.let { "${TOOLS.getValue(toolName).localized()}: ${it.localized()}" }
    }?.let { return it }
    return humanize(name, fallback = tr("Инструмент", "Tool"))
  }

  /**
   * The caption of one call — for the confirmation bar and the call log: the tool plus the action, if
   * known ("Calendar: delete event"). An unknown action is not shown at all — plain "Calendar" is better
   * than a raw id after a colon.
   */
  fun call(name: String, arguments: Map<String, JsonElement>): String {
    val tool = tool(name)
    if (name == LOAD_SKILL) {
      arguments.string("id")?.let { id ->
        val skillTitle = skill(id)
        return "$tool: " + tr("загрузить «$skillTitle»", "load “$skillTitle”")
      }
    }
    val action = arguments.string("action")?.let { ACTIONS[name]?.get(it) } ?: return tool
    return "$tool: ${action.localized()}"
  }

  /**
   * Name of a built-in skill. The user's own skills live in Room under their own titles — the screen
   * that has database access substitutes those; they get here only as a fallback.
   */
  fun skill(id: String): String = SKILLS[id]?.localized() ?: humanize(id, fallback = tr("Навык", "Skill"))

  /** The (ru, en) pair in the interface language — picked at call time, not when the dictionary is initialized. */
  private fun Pair<String, String>.localized(): String = tr(first, second)

  /** `weather_api` → "Weather api": readable, no underscores; an empty id gives a generic word. */
  private fun humanize(raw: String, fallback: String): String {
    val words = raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")
    return if (words.isEmpty()) fallback else words.replaceFirstChar { it.uppercaseChar() }
  }
}
