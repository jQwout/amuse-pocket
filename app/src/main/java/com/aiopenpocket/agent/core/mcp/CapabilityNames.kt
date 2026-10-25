package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import kotlinx.serialization.json.JsonElement

/**
 * Единственный источник человеческих имён для инструментов, их действий и встроенных навыков.
 * Сырые id (`memory_tool`, `search_memory`, `budget`) — контракт с моделью, пользователю они ничего
 * не говорят. Раньше каждый экран переводил их сам: чипы «Умеет» и конструктор — каждый своим
 * словарём (одно и то же звалось то «Словарь», то «Словарь (SRS)»), а бар подтверждения, журнал
 * вызовов и экран агента показывали id как есть, моноширинным шрифтом.
 *
 * Лежит рядом с [Tool], а не в ui: подпись «Использую инструмент: …» в пузыре собирает слой цикла
 * агента, а он в ui не ходит. Чистый Kotlin — проверяется обычным JUnit.
 *
 * Ничего не бросает и никогда не отдаёт пустую строку: незнакомое имя (тул из более новой версии
 * приложения, чужой навык) превращается в читаемую подпись из самого id.
 */
object CapabilityNames {

  /** Имена тулов из [com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_TOOL_NAMES]. */
  private val TOOLS: Map<String, String> = mapOf(
    "memory_tool" to "Память",
    "storage_tool" to "Хранилище агента",
    "file_tool" to "Файлы агента",
    "external_files" to "Внешние файлы",
    "render_card" to "Карточки",
    "notifications" to "Уведомления",
    "heartbeat" to "Напоминания",
    "work_manager" to "Фоновые задачи",
    "calendar" to "Календарь",
    "alarm" to "Будильник",
    "email" to "Почта",
    "srs_tool" to "Словарь",
    "step_counter" to "Шагомер",
    "load_skill" to "Навыки",
    "database_query" to "Запросы к базе",
  )

  /** Общие действия файловых тулов (песочница агента и внешние файлы). Объявлены до [ACTIONS] — порядок инициализации. */
  private val FILE_ACTIONS: Map<String, String> = mapOf(
    "read_file" to "прочитать файл",
    "write_file" to "записать файл",
    "list_dir" to "показать папку",
  )

  /**
   * Действия внутри тулов — значение аргумента `action`, то есть то, что модель реально просит
   * сделать. По тулу, а не одним списком: `read_file`/`list` есть у нескольких тулов сразу.
   */
  private val ACTIONS: Map<String, Map<String, String>> = mapOf(
    "memory_tool" to mapOf(
      "remember_fact" to "запомнить факт",
      "search_memory" to "найти факты",
      "recall_episodes" to "вспомнить разговоры",
    ),
    "storage_tool" to mapOf(
      "put" to "сохранить запись",
      "get" to "прочитать запись",
      "list" to "показать записи",
      "delete" to "удалить запись",
    ),
    "file_tool" to FILE_ACTIONS + ("search_files" to "найти файлы"),
    "external_files" to FILE_ACTIONS,
    "heartbeat" to mapOf(
      "schedule" to "запланировать",
      "cancel" to "отменить",
      "list" to "показать запланированные",
    ),
    "work_manager" to mapOf(
      "schedule_notification" to "запланировать уведомление",
      "schedule_periodic" to "запланировать повтор",
      "cancel_work" to "отменить задачу",
    ),
    "calendar" to mapOf(
      "list_events" to "показать события",
      "create_event" to "создать событие",
      "delete_event" to "удалить событие",
    ),
    "alarm" to mapOf(
      "set_alarm" to "завести",
      "show_alarms" to "показать все",
    ),
    "email" to mapOf("compose" to "написать письмо"),
    "srs_tool" to mapOf(
      "add_word" to "добавить слово",
      "due_words" to "что повторить сегодня",
      "review" to "отметить повторение",
      "all_words" to "показать все слова",
    ),
    "step_counter" to mapOf("snapshot" to "узнать шаги"),
  )

  /** Названия встроенных навыков ([com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_SKILL_IDS]). */
  private val SKILLS: Map<String, String> = mapOf(
    "budget" to "Бюджет",
    "diary" to "Дневник",
    "fitness" to "Фитнес",
    "languages" to "Языки",
  )

  private const val LOAD_SKILL = "load_skill"

  /** Есть ли у тула своё имя — чипам «Умеет» незнакомый тул лучше пропустить, чем подписать по id. */
  fun isKnownTool(name: String): Boolean = name in TOOLS

  /**
   * Короткое имя тула: «Память», «Будильник». Понимает и имя действия, если кто-то передал его как
   * имя функции (`search_memory` → «Память: найти факты»).
   */
  fun tool(name: String): String {
    TOOLS[name]?.let { return it }
    ACTIONS.entries.firstNotNullOfOrNull { (toolName, actions) ->
      actions[name]?.let { "${TOOLS.getValue(toolName)}: $it" }
    }?.let { return it }
    return humanize(name, fallback = "Инструмент")
  }

  /**
   * Подпись одного вызова — для бара подтверждения и журнала вызовов: тул плюс действие, если оно
   * известно («Календарь: удалить событие»). Незнакомое действие не показываем вовсе — лучше просто
   * «Календарь», чем сырой id после двоеточия.
   */
  fun call(name: String, arguments: Map<String, JsonElement>): String {
    val tool = tool(name)
    if (name == LOAD_SKILL) {
      arguments.string("id")?.let { return "$tool: загрузить «${skill(it)}»" }
    }
    val action = arguments.string("action")?.let { ACTIONS[name]?.get(it) } ?: return tool
    return "$tool: $action"
  }

  /**
   * Название встроенного навыка. Свои навыки пользователя живут в Room под его же названием — их
   * подставляет экран, у которого есть доступ к базе; сюда они попадают только как запасной вариант.
   */
  fun skill(id: String): String = SKILLS[id] ?: humanize(id, fallback = "Навык")

  /** `weather_api` → «Weather api»: читаемо, без подчёркиваний; пустой id — общее слово. */
  private fun humanize(raw: String, fallback: String): String {
    val words = raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")
    return if (words.isEmpty()) fallback else words.replaceFirstChar { it.uppercaseChar() }
  }
}
