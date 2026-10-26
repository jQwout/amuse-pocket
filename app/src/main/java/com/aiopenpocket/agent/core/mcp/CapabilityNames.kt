package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.i18n.tr
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
 *
 * Каждое имя хранится парой «русский — английский», язык выбирается в момент вызова ([tr]), а не при
 * инициализации словарей: иначе подписи застыли бы на языке первого обращения.
 */
object CapabilityNames {

  /** Имена тулов из [com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_TOOL_NAMES]: id → (ru, en). */
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
  )

  /** Общие действия файловых тулов (песочница агента и внешние файлы). Объявлены до [ACTIONS] — порядок инициализации. */
  private val FILE_ACTIONS: Map<String, Pair<String, String>> = mapOf(
    "read_file" to ("прочитать файл" to "read a file"),
    "write_file" to ("записать файл" to "write a file"),
    "list_dir" to ("показать папку" to "list a folder"),
  )

  /**
   * Действия внутри тулов — значение аргумента `action`, то есть то, что модель реально просит
   * сделать. По тулу, а не одним списком: `read_file`/`list` есть у нескольких тулов сразу.
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
  )

  /** Названия встроенных навыков ([com.aiopenpocket.agent.core.agent.AgentValidator.KNOWN_SKILL_IDS]). */
  private val SKILLS: Map<String, Pair<String, String>> = mapOf(
    "budget" to ("Бюджет" to "Budget"),
    "diary" to ("Дневник" to "Diary"),
    "fitness" to ("Фитнес" to "Fitness"),
    "languages" to ("Языки" to "Languages"),
  )

  private const val LOAD_SKILL = "load_skill"

  /** Есть ли у тула своё имя — чипам «Умеет» незнакомый тул лучше пропустить, чем подписать по id. */
  fun isKnownTool(name: String): Boolean = name in TOOLS

  /**
   * Короткое имя тула: «Память», «Будильник». Понимает и имя действия, если кто-то передал его как
   * имя функции (`search_memory` → «Память: найти факты»).
   */
  fun tool(name: String): String {
    TOOLS[name]?.let { return it.localized() }
    ACTIONS.entries.firstNotNullOfOrNull { (toolName, actions) ->
      actions[name]?.let { "${TOOLS.getValue(toolName).localized()}: ${it.localized()}" }
    }?.let { return it }
    return humanize(name, fallback = tr("Инструмент", "Tool"))
  }

  /**
   * Подпись одного вызова — для бара подтверждения и журнала вызовов: тул плюс действие, если оно
   * известно («Календарь: удалить событие»). Незнакомое действие не показываем вовсе — лучше просто
   * «Календарь», чем сырой id после двоеточия.
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
   * Название встроенного навыка. Свои навыки пользователя живут в Room под его же названием — их
   * подставляет экран, у которого есть доступ к базе; сюда они попадают только как запасной вариант.
   */
  fun skill(id: String): String = SKILLS[id]?.localized() ?: humanize(id, fallback = tr("Навык", "Skill"))

  /** Пара (ru, en) на языке интерфейса — выбор в момент вызова, не при инициализации словаря. */
  private fun Pair<String, String>.localized(): String = tr(first, second)

  /** `weather_api` → «Weather api»: читаемо, без подчёркиваний; пустой id — общее слово. */
  private fun humanize(raw: String, fallback: String): String {
    val words = raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")
    return if (words.isEmpty()) fallback else words.replaceFirstChar { it.uppercaseChar() }
  }
}
