package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.i18n.tr

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
 * [RiskClass.DESTRUCTIVE]). Every other entry's [riskClass] matches its tool's own declared ceiling
 * (`storage_tool` and `external_files` declare DESTRUCTIVE since their delete/overwrite actions do).
 */
data class ToolCatalogEntry(
  val name: String,
  val riskClass: RiskClass,
  val permission: String?,
  /** [consequence] in Russian. Both versions are stored side by side, the language is picked on display. */
  val consequenceRu: String,
  /** [consequence] in English. */
  val consequenceEn: String,
  /** Hidden from [ToolCatalog.default] — only offered via the CAPABILITIES step's "Advanced tools" expander. */
  val advancedOnly: Boolean = false,
) {
  /**
   * What the tool does with the user's data — in the interface language. A getter, not a field:
   * [ToolCatalog] is built once per process, and a text chosen at initialization would freeze in that
   * language.
   */
  val consequence: String get() = tr(consequenceRu, consequenceEn)

  /**
   * The tool's name comes from the shared [CapabilityNames], not its own: the builder, the "Can do"
   * chips, the agent screen and the confirmation bar must all call one tool the same way (there used to
   * be a separate dictionary here).
   */
  val label: String get() = CapabilityNames.tool(name)

  /**
   * Whether selecting this tool must go through [com.aiopenpocket.ui.agents.builder.components.RiskConsentDialog]
   * first. Every non-[RiskClass.READ] tool gates on consent; [advancedOnly] tools ALSO gate on it even
   * where their nominal ceiling reads [RiskClass.READ] (`database_query`'s default action) — arbitrary
   * SQL is sensitive enough to still ask, on top of already being hidden behind the advanced-tools
   * expander itself.
   */
  val requiresConsent: Boolean get() = riskClass != RiskClass.READ || advancedOnly
}

/**
 * Static, UI-side mirror of [AgentValidator.KNOWN_TOOL_NAMES] for the CAPABILITIES step. Duplicated
 * rather than resolved live: the actual `Tool` beans are a DI multibinding (`Set<Tool>`) built from
 * constructor dependencies (repositories, `Context`) that nothing in the builder wizard resolves or
 * should resolve — the same trade-off `AgentValidator.KNOWN_TOOL_NAMES` itself already made.
 *
 * It is also THE source of the risk ceiling outside the wizard: the agent inspector
 * ([com.aiopenpocket.ui.agents.detail.AgentDetailViewModel]) reads [get] instead of each tool's own
 * `Tool.riskClass`, so one screen cannot promise a narrower right than the one the other screen took
 * the user's consent for. Keep the ceilings here and nowhere else — a second table would let the two
 * drift apart again; the parity is held by a test (`AgentDetailViewModelTest`).
 */
object ToolCatalog {

  val entries: List<ToolCatalogEntry> = listOf(
    ToolCatalogEntry(
      name = "memory_tool",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequenceRu = "Может сохранять факты о тебе в долговременную память агента.",
      consequenceEn = "Can save facts about you to the agent's long-term memory.",
    ),
    ToolCatalogEntry(
      name = "storage_tool",
      // DESTRUCTIVE is the worst case: delete erases the agent's data (reclassified in the tool itself on
      // Oct 25, 2026); every delete in the chat asks for permission, and it is forbidden in the background.
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      // Part B of the database_query security hardening (user-agent-builder-848153, 2026-07-15): the
      // structured write path database_query's Part A restriction pushes agents toward. Isolated
      // per-agent IN CODE (agentId comes only from TurnContextHolder, never a tool argument) — safe as
      // a default (non-advanced) tool for exactly the same reason memory_tool already is one.
      consequenceRu = "Сохраняет, читает и удаляет собственные данные агента (заметки, счётчики) в " +
        "изолированном хранилище — другие агенты его не видят. Удаление каждый раз спросит твоё разрешение.",
      consequenceEn = "Saves, reads and deletes the agent's own data (notes, counters) in isolated " +
        "storage — other agents can't see it. Every deletion asks for your permission.",
    ),
    ToolCatalogEntry(
      name = "file_tool",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequenceRu = "Создаёт, читает и изменяет файлы агента в его собственной песочнице.",
      consequenceEn = "Creates, reads and edits the agent's files in its own sandbox.",
    ),
    // advancedOnly, and the consequence says plainly that nothing works yet: the tool only accepts
    // content:// URIs the user has already granted (see ExternalFilesTool.rejectionReason), and this
    // app still has no SAF picker to grant any. Promising "pick a file" in the default picker would
    // sell a capability that cannot fire — and the pre-gate version only "worked" by reading the
    // app's own private storage through file://, which is exactly what the gate now refuses.
    ToolCatalogEntry(
      name = "external_files",
      // DESTRUCTIVE: write_file overwrites the user's document entirely (the "wt" mode).
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      consequenceRu = "Читает и пишет файлы вне песочницы — только те, доступ к которым пользователь " +
        "уже выдал системе. Выбора папки в приложении пока нет, поэтому тул сейчас всегда отвечает " +
        "отказом: для файлов агента используй «Файлы агента».",
      consequenceEn = "Reads and writes files outside the sandbox — only ones you've already granted " +
        "access to in the system. The app has no folder picker yet, so this tool always refuses for " +
        "now: for the agent's own files use “Agent files”.",
      advancedOnly = true,
    ),
    ToolCatalogEntry(
      name = "render_card",
      riskClass = RiskClass.READ,
      permission = null,
      consequenceRu = "Показывает интерактивные карточки в чате.",
      consequenceEn = "Shows interactive cards in the chat.",
    ),
    ToolCatalogEntry(
      name = "notifications",
      riskClass = RiskClass.WRITE,
      permission = "POST_NOTIFICATIONS",
      consequenceRu = "Показывает системные push-уведомления.",
      consequenceEn = "Shows system push notifications.",
    ),
    ToolCatalogEntry(
      name = "heartbeat",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      consequenceRu = "Планирует фоновые пробуждения агента и может безвозвратно отменять уже запланированные.",
      consequenceEn = "Schedules background wake-ups for the agent and can permanently cancel ones already scheduled.",
    ),
    ToolCatalogEntry(
      name = "work_manager",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      consequenceRu = "Планирует фоновые задачи и может безвозвратно отменять уже запланированные.",
      consequenceEn = "Schedules background tasks and can permanently cancel ones already scheduled.",
    ),
    ToolCatalogEntry(
      name = "calendar",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = "READ_CALENDAR, WRITE_CALENDAR",
      consequenceRu = "Создаёт, изменяет и безвозвратно удаляет события в календаре устройства.",
      consequenceEn = "Creates, edits and permanently deletes events in the device calendar.",
    ),
    ToolCatalogEntry(
      name = "alarm",
      riskClass = RiskClass.WRITE,
      permission = "SET_ALARM",
      consequenceRu = "Ставит будильники на устройстве.",
      consequenceEn = "Sets alarms on the device.",
    ),
    ToolCatalogEntry(
      name = "email",
      riskClass = RiskClass.EXTERNAL,
      permission = null,
      consequenceRu = "Готовит черновик письма в почтовом приложении (без автоотправки) — данные покидают устройство.",
      consequenceEn = "Prepares an email draft in your mail app (never sends it on its own) — data leaves the device.",
    ),
    ToolCatalogEntry(
      name = "srs_tool",
      riskClass = RiskClass.WRITE,
      permission = null,
      consequenceRu = "Сохраняет слова и планирует их повторение — записывает данные на устройстве.",
      consequenceEn = "Saves words and schedules their review — writes data on the device.",
    ),
    ToolCatalogEntry(
      name = "step_counter",
      riskClass = RiskClass.READ,
      permission = "ACTIVITY_RECOGNITION",
      consequenceRu = "Читает счётчик шагов с датчика устройства.",
      consequenceEn = "Reads the step count from the device sensor.",
    ),
    ToolCatalogEntry(
      name = "load_skill",
      riskClass = RiskClass.READ,
      permission = null,
      consequenceRu = "Подгружает подробную инструкцию навыка в контекст агента.",
      consequenceEn = "Loads a skill's detailed instructions into the agent's context.",
    ),
    ToolCatalogEntry(
      name = "database_query",
      riskClass = RiskClass.DESTRUCTIVE,
      permission = null,
      // Read-only since Part A of the database_query security hardening (user-agent-builder-848153,
      // 2026-07-15): only SELECT against a fixed table allowlist now runs at all — INSERT/UPDATE/
      // DELETE are rejected outright, not just risk-classified differently. riskClass is deliberately
      // LEFT at DESTRUCTIVE regardless (not downgraded to READ): this catalog's own doc-comment above
      // reports the worst case a tool COULD reach, and an over-cautious label here costs nothing,
      // whereas a future write path re-added to the tool without this catalog entry being revisited
      // would otherwise silently under-warn.
      consequenceRu = "Выполняет только SELECT-запросы (чтение) к локальной базе приложения, по " +
        "фиксированному списку таблиц — системные таблицы агентов недоступны. Запись не поддерживается.",
      consequenceEn = "Runs read-only SELECT queries against the app's local database, on a fixed list " +
        "of tables — the agents' system tables are off-limits. Writing isn't supported.",
      advancedOnly = true,
    ),
  )

  /** Default tool picker set — every known tool except [advanced]. */
  val default: List<ToolCatalogEntry> = entries.filterNot { it.advancedOnly }

  /** Only reachable via the CAPABILITIES step's explicit "Advanced tools" expander. */
  val advanced: List<ToolCatalogEntry> = entries.filter { it.advancedOnly }

  fun get(name: String): ToolCatalogEntry? = entries.find { it.name == name }
}

/** Short label for [RiskClass] on the UI language — shown on every tool row's risk badge and the chat approval bar. */
fun riskClassLabel(risk: RiskClass): String = when (risk) {
  RiskClass.READ -> tr("Только чтение", "Read only")
  RiskClass.WRITE -> tr("Изменяет данные", "Changes data")
  RiskClass.DESTRUCTIVE -> tr("Может удалить необратимо", "Can delete permanently")
  RiskClass.EXTERNAL -> tr("Покидает устройство", "Leaves the device")
}
