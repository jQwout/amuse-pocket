package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.mcp.RiskClass

/**
 * One [AgentValidator.KNOWN_TOOL_NAMES] entry, described for the CAPABILITIES step's risk rail: a
 * human [label], the worst-case [riskClass] this tool can produce, the Android [permission] it needs
 * (if any — matches `AndroidManifest.xml`), and a short [consequence] line shown in
 * [com.aiopenpocket.ui.agents.builder.components.RiskConsentDialog].
 *
 * [riskClass] here is a DELIBERATE judgment call, not a verbatim copy of each tool's own
 * `Tool.riskClass` (verified against `app/src/main/java/com/example/agent/tools` 2026-07-13):
 * several tools (`memory_tool`, `heartbeat`, `database_query`) never override their base
 * `Tool.riskClass` (so it defaults to [RiskClass.READ]) even though their `riskFor(arguments)`
 * override escalates per call — e.g. `heartbeat`'s `cancel` action is [RiskClass.DESTRUCTIVE] despite
 * the class-wide ceiling reading [RiskClass.READ]. A builder risk rail exists to warn the user BEFORE
 * they know which arguments the model will eventually pass, so this catalog reports the actual WORST
 * case any documented action of that tool can reach, not the possibly-understated class-wide default.
 * `work_manager` is the other such case (ceiling declared [RiskClass.WRITE], `cancel_work` actually
 * [RiskClass.DESTRUCTIVE]). Every other entry's [riskClass] matches its tool's own declared ceiling.
 */
data class ToolCatalogEntry(
  val name: String,
  val label: String,
  val riskClass: RiskClass,
  val permission: String?,
  val consequence: String,
  /** Hidden from [ToolCatalog.default] — only offered via the CAPABILITIES step's "Advanced tools" expander. */
  val advancedOnly: Boolean = false,
) {
  /**
   * Whether selecting this tool must go through [com.aiopenpocket.ui.agents.builder.components.RiskConsentDialog]
   * first. Every non-[RiskClass.READ] tool gates on consent; [advancedOnly] tools ALSO gate on it even
   * where their nominal ceiling reads [RiskClass.READ] (`database_query`'s default action, `sms`'s
   * only action) — both are sensitive enough (arbitrary SQL; reading private messages) to still ask,
   * on top of already being hidden behind the advanced-tools expander itself.
   */
  val requiresConsent: Boolean get() = riskClass != RiskClass.READ || advancedOnly
}

/**
 * Static, UI-side mirror of [AgentValidator.KNOWN_TOOL_NAMES] for the CAPABILITIES step. Duplicated
 * rather than resolved live: the actual `Tool` beans are a DI multibinding (`Set<Tool>`) built from
 * constructor dependencies (repositories, `Context`) that nothing in the builder wizard resolves or
 * should resolve — the same trade-off `AgentValidator.KNOWN_TOOL_NAMES` itself already made.
 */
object ToolCatalog {

  val entries: List<ToolCatalogEntry> = listOf(
    ToolCatalogEntry(
      name = "memory_tool",
      label = "Память",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequence = "Может сохранять факты о тебе в долговременную память агента.",
    ),
    ToolCatalogEntry(
      name = "storage_tool",
      label = "Хранилище агента",
      riskClass = RiskClass.WRITE,
      permission = null,
      // Part B of the database_query security hardening (user-agent-builder-848153, 2026-07-15): the
      // structured write path database_query's Part A restriction pushes agents toward. Isolated
      // per-agent IN CODE (agentId comes only from TurnContextHolder, never a tool argument) — safe as
      // a default (non-advanced) tool for exactly the same reason memory_tool already is one.
      consequence = "Сохраняет и читает собственные данные агента (заметки, счётчики) в изолированном " +
        "хранилище — другие агенты его не видят.",
    ),
    ToolCatalogEntry(
      name = "file_tool",
      label = "Файлы агента",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequence = "Создаёт, читает и изменяет файлы агента в его собственной песочнице.",
    ),
    ToolCatalogEntry(
      name = "external_files",
      label = "Внешние файлы",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequence = "Читает и пишет файлы вне песочницы приложения — через системный диалог выбора файла.",
    ),
    ToolCatalogEntry(
      name = "render_card",
      label = "Карточки",
      riskClass = RiskClass.READ,
      permission = null,
      consequence = "Показывает интерактивные карточки в чате.",
    ),
    ToolCatalogEntry(
      name = "notifications",
      label = "Уведомления",
      riskClass = RiskClass.WRITE,
      permission = "POST_NOTIFICATIONS",
      consequence = "Показывает системные push-уведомления.",
    ),
    ToolCatalogEntry(
      name = "heartbeat",
      label = "Фоновые напоминания",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      consequence = "Планирует фоновые пробуждения агента и может безвозвратно отменять уже запланированные.",
    ),
    ToolCatalogEntry(
      name = "work_manager",
      label = "Фоновые задачи",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      consequence = "Планирует фоновые задачи и может безвозвратно отменять уже запланированные.",
    ),
    ToolCatalogEntry(
      name = "calendar",
      label = "Календарь",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = "READ_CALENDAR, WRITE_CALENDAR",
      consequence = "Создаёт, изменяет и безвозвратно удаляет события в календаре устройства.",
    ),
    ToolCatalogEntry(
      name = "alarm",
      label = "Будильник",
      riskClass = RiskClass.WRITE,
      permission = "SET_ALARM",
      consequence = "Ставит будильники на устройстве.",
    ),
    ToolCatalogEntry(
      name = "email",
      label = "Почта",
      riskClass = RiskClass.EXTERNAL,
      permission = null,
      consequence = "Готовит черновик письма в почтовом приложении (без автоотправки) — данные покидают устройство.",
    ),
    ToolCatalogEntry(
      name = "srs_tool",
      label = "Словарь (SRS)",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequence = "Сохраняет слова и планирует их повторение — записывает данные на устройстве.",
    ),
    ToolCatalogEntry(
      name = "step_counter",
      label = "Шагомер",
      riskClass = RiskClass.READ,
      permission = "ACTIVITY_RECOGNITION",
      consequence = "Читает счётчик шагов с датчика устройства.",
    ),
    ToolCatalogEntry(
      name = "load_skill",
      label = "Загрузка навыков",
      riskClass = RiskClass.READ,
      permission = null,
      consequence = "Подгружает подробную инструкцию навыка в контекст агента.",
    ),
    ToolCatalogEntry(
      name = "database_query",
      label = "SQL-запросы к базе",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      // Read-only since Part A of the database_query security hardening (user-agent-builder-848153,
      // 2026-07-15): only SELECT against a fixed table allowlist now runs at all — INSERT/UPDATE/
      // DELETE are rejected outright, not just risk-classified differently. riskClass is deliberately
      // LEFT at DESTRUCTIVE regardless (not downgraded to READ): this catalog's own doc-comment above
      // reports the worst case a tool COULD reach, and an over-cautious label here costs nothing,
      // whereas a future write path re-added to the tool without this catalog entry being revisited
      // would otherwise silently under-warn.
      consequence = "Выполняет только SELECT-запросы (чтение) к локальной базе приложения, по " +
        "фиксированному списку таблиц — системные таблицы агентов недоступны. Запись не поддерживается.",
      advancedOnly = true,
    ),
    ToolCatalogEntry(
      name = "sms",
      label = "Чтение SMS",
      riskClass = RiskClass.READ,
      permission = "READ_SMS",
      consequence = "Читает последние входящие SMS пользователя (без отправки).",
      advancedOnly = true,
    ),
  )

  /** Default tool picker set — every known tool except [advanced]. */
  val default: List<ToolCatalogEntry> = entries.filterNot { it.advancedOnly }

  /** Only reachable via the CAPABILITIES step's explicit "Advanced tools" expander. */
  val advanced: List<ToolCatalogEntry> = entries.filter { it.advancedOnly }

  fun get(name: String): ToolCatalogEntry? = entries.find { it.name == name }
}

/** Short Russian label for [RiskClass] — shown on every tool row's risk badge. */
fun riskClassLabel(risk: RiskClass): String = when (risk) {
  RiskClass.READ -> "Только чтение"
  RiskClass.WRITE -> "Изменяет данные"
  RiskClass.DESTRUCTIVE -> "Может удалить необратимо"
  RiskClass.EXTERNAL -> "Покидает устройство"
}
