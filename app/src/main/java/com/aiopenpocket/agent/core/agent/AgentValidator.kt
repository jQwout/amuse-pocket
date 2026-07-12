package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

/**
 * One rule violation found by [AgentValidator.validate]. [code] is a stable, machine-checkable
 * identifier (assert on it, branch a builder UI on it); [message] is a human-readable (Russian,
 * matching the app's UI language) explanation; [field] optionally names the offending
 * [AgentDefinition] property — for set-valued fields, the specific bad entry — so a future form
 * can attach the error inline instead of only showing a flat list.
 */
data class ValidationIssue(
  val code: String,
  val message: String,
  val field: String? = null,
)

/**
 * Pure, stateless rules an [AgentDefinition] must satisfy before the runtime (`AgentResolver`,
 * `ExecutionPolicy`, the builder wizard) can trust it. [validate] never throws and never
 * short-circuits on the first problem — it collects every violation so a form can surface them
 * all at once instead of one round-trip per fix.
 *
 * v1 scope (`user-agent-builder-848153` Task 1): field shape/limits, ID collisions with the
 * read-only preset catalog, default-mode membership, the per-agent memory namespace shape, and
 * known-registry membership for tools/skills/widget templates. Cross-entity checks (duplicate
 * custom agent names, hook wiring, Room round-trips) land with the repository/hook engine in
 * later tasks.
 */
object AgentValidator {

  const val MAX_SYSTEM_PROMPT_CHARS = 20_000
  const val MAX_AGENT_NAME_CHARS = 60
  const val MAX_DESCRIPTION_CHARS = 240

  /**
   * Cap for a custom skill's inline instruction body. Not enforced by [validate] yet —
   * [AgentDefinition] only carries skill *ids* ([AgentDefinition.skillIds]); this limit applies to
   * the user-authored skill *content* a later task's custom skill entity/validator will check.
   * Declared now (with the rest of the v1 limits) so its value is frozen ahead of that task.
   */
  const val MAX_CUSTOM_SKILL_CHARS = 40_000

  /** One lower-alnum, then 2-63 more lower-alnum/hyphen/underscore — 3 to 64 chars total. */
  private val ID_REGEX = Regex("[a-z0-9][a-z0-9_-]{2,63}")

  /**
   * [com.aiopenpocket.agent.core.mcp.Tool.name] of every tool registered in `AgentToolsModule` /
   * `SkillModule` (verified against that source 2026-07-12). No single tool-name catalog exists to
   * import instead yet — kept in sync by hand until a later task exports one.
   */
  val KNOWN_TOOL_NAMES: Set<String> = setOf(
    "memory_tool", "file_tool", "external_files", "database_query", "render_card",
    "notifications", "heartbeat", "work_manager", "calendar", "alarm", "email", "sms",
    "step_counter", "srs_tool", "load_skill",
  )

  /** `SkillManifest.id` of every `Skill` bean in `SkillModule` (verified against that source 2026-07-12). */
  val KNOWN_SKILL_IDS: Set<String> = setOf("budget", "diary", "fitness", "languages")

  /**
   * Mirrors `RenderCardTool`'s private `CARD_TEMPLATES` (verified 2026-07-12). Duplicated, not
   * imported: that list is file-private and `RenderCardTool.kt` is out of this task's file scope —
   * kept in sync by hand until a later task exports it.
   */
  val KNOWN_WIDGET_TEMPLATES: Set<String> = setOf(
    "todo_list", "weather_card", "timer", "chart_bar", "table", "form", "choice", "lesson_quiz",
    "lesson_complete", "switch", "notification_permission", "reminder", "image", "reveal",
    "concept", "example", "fill", "sort", "match", "scenario",
  )

  fun validate(definition: AgentDefinition): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()

    if (!ID_REGEX.matches(definition.id)) {
      issues += ValidationIssue(
        code = "invalid_id_format",
        message = "id должен соответствовать формату ${ID_REGEX.pattern} (3-64 символа)",
        field = "id",
      )
    }

    if (definition.name.isBlank()) {
      issues += ValidationIssue("blank_name", "Имя агента не может быть пустым", "name")
    } else if (definition.name.length > MAX_AGENT_NAME_CHARS) {
      issues += ValidationIssue("name_too_long", "Имя длиннее $MAX_AGENT_NAME_CHARS символов", "name")
    }

    if (definition.description.isBlank()) {
      issues += ValidationIssue("blank_description", "Описание не может быть пустым", "description")
    } else if (definition.description.length > MAX_DESCRIPTION_CHARS) {
      issues += ValidationIssue("description_too_long", "Описание длиннее $MAX_DESCRIPTION_CHARS символов", "description")
    }

    if (definition.iconKey.isBlank()) {
      issues += ValidationIssue("blank_icon_key", "iconKey не может быть пустым", "iconKey")
    }

    if (definition.systemPrompt.isBlank()) {
      issues += ValidationIssue("blank_system_prompt", "Системный промпт не может быть пустым", "systemPrompt")
    } else if (definition.systemPrompt.length > MAX_SYSTEM_PROMPT_CHARS) {
      issues += ValidationIssue("system_prompt_too_long", "Промпт длиннее $MAX_SYSTEM_PROMPT_CHARS символов", "systemPrompt")
    }

    // Only a USER-sourced agent can "collide" with a preset id — a preset's own adapted
    // definition legitimately reuses its own catalog id (that is not identity theft of itself).
    if (definition.source == AgentSource.USER && MiniAgentCatalog.get(definition.id) != null) {
      issues += ValidationIssue("preset_id_collision", "id '${definition.id}' уже занят пресетом", "id")
    }

    if (definition.defaultMode !in definition.allowedModes) {
      issues += ValidationIssue("default_mode_not_allowed", "defaultMode должен входить в allowedModes", "defaultMode")
    }

    val expectedNamespace = "agent:${definition.id}:"
    if (definition.memoryPolicy.namespace != expectedNamespace) {
      issues += ValidationIssue(
        code = "invalid_memory_namespace",
        message = "memoryPolicy.namespace должен быть '$expectedNamespace'",
        field = "memoryPolicy.namespace",
      )
    }

    for (tool in definition.toolNames) {
      if (tool !in KNOWN_TOOL_NAMES) {
        issues += ValidationIssue("unknown_tool", "Неизвестный инструмент '$tool'", "toolNames")
      }
    }
    for (skill in definition.skillIds) {
      if (skill !in KNOWN_SKILL_IDS) {
        issues += ValidationIssue("unknown_skill", "Неизвестный навык '$skill'", "skillIds")
      }
    }
    for (widget in definition.allowedWidgetTemplates) {
      if (widget !in KNOWN_WIDGET_TEMPLATES) {
        issues += ValidationIssue("unknown_widget_template", "Неизвестный шаблон карточки '$widget'", "allowedWidgetTemplates")
      }
    }

    return issues
  }
}
