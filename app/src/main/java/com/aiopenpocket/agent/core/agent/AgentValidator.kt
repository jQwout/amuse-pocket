package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.i18n.tr

/**
 * One rule violation found by [AgentValidator.validate]. [code] is a stable, machine-checkable
 * identifier (assert on it, branch a builder UI on it); [message] is a human-readable explanation
 * in the UI language current at validation time (Russian by default, English via `i18n.tr`); [field] optionally names the offending
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
 * read-only preset catalog, default-mode membership, the per-agent memory namespace shape,
 * known-registry membership for tools/skills/widget templates, and (Task 8) each hook's
 * [com.aiopenpocket.agent.core.hook.HookActionType] against its own
 * [com.aiopenpocket.agent.core.hook.HookEvent]'s action matrix. Checks that need Room (duplicate
 * custom agent names, hook/skill id ownership collisions) land with the repository instead —
 * [validate] stays pure/stateless.
 */
object AgentValidator {

  const val MAX_SYSTEM_PROMPT_CHARS = 20_000
  const val MAX_AGENT_NAME_CHARS = 60
  const val MAX_DESCRIPTION_CHARS = 240

  /**
   * Cap for a custom skill's inline instruction body. Not enforced by [validate] — [AgentDefinition]
   * only carries skill *ids* ([AgentDefinition.skillIds]), never skill *content* — but by
   * `com.aiopenpocket.agent.core.skill.validateUserSkill(UserSkillEntity)` (Task 4), which runs
   * against the real `UserSkillEntity.instructionsMarkdown` inside
   * [UserAgentRepository.publish]'s transaction. Declared here (with the rest of the v1 limits) so
   * its value stays the single frozen source both validators agree on.
   */
  const val MAX_CUSTOM_SKILL_CHARS = 40_000

  /**
   * One lower-alnum, then 2-63 more lower-alnum/hyphen/underscore — 3 to 64 chars total. `internal`
   * (not `private`): also reused by `com.aiopenpocket.agent.tools.sandboxRoot` to fail closed on a
   * non-conforming `agentId` at the file-sandbox boundary, so the two checks can never drift apart.
   */
  internal val ID_REGEX = Regex("[a-z0-9][a-z0-9_-]{2,63}")

  /**
   * [com.aiopenpocket.agent.core.mcp.Tool.name] of every tool registered in `AgentToolsModule` /
   * `SkillModule` (verified against that source 2026-07-15 — `storage_tool` added by Part B of the
   * database_query security hardening, `user-agent-builder-848153`). No single tool-name catalog
   * exists to import instead yet — kept in sync by hand until a later task exports one.
   */
  val KNOWN_TOOL_NAMES: Set<String> = setOf(
    "memory_tool", "file_tool", "external_files", "database_query", "render_card",
    "notifications", "heartbeat", "work_manager", "calendar", "alarm", "email",
    "step_counter", "srs_tool", "load_skill", "storage_tool",
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

  /**
   * [ownedCustomSkillIds] — this specific agent's own Room custom skill ids (Task 4's
   * `user_skills`), passed by [UserAgentRepository.publish] — the only impure caller with Room
   * access — so a custom agent can reference a skill it authored itself without [validate] itself
   * touching Room (it stays pure/stateless). Every other caller (tests, [requirePublishable]'s
   * default) leaves this empty, so a `skillIds` entry that is neither a known built-in nor
   * explicitly passed here is still `unknown_skill` — unchanged v1 behavior for anyone who hasn't
   * wired custom skills through yet.
   */
  fun validate(definition: AgentDefinition, ownedCustomSkillIds: Set<String> = emptySet()): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()

    // [ValidationIssue.message] texts are in the interface language at the moment of the check ([tr]);
    // the wizard re-validates the draft on every edit, so the strings do not freeze.
    // [ValidationIssue.code] does not depend on the language — tests/UI branch on it.
    if (!ID_REGEX.matches(definition.id)) {
      issues += ValidationIssue(
        code = "invalid_id_format",
        message = tr(
          "id должен соответствовать формату ${ID_REGEX.pattern} (3-64 символа)",
          "id must match the format ${ID_REGEX.pattern} (3-64 characters)",
        ),
        field = "id",
      )
    }

    if (definition.name.isBlank()) {
      issues += ValidationIssue("blank_name", tr("Имя агента не может быть пустым", "Agent name can't be empty"), "name")
    } else if (definition.name.length > MAX_AGENT_NAME_CHARS) {
      issues += ValidationIssue(
        "name_too_long",
        tr("Имя длиннее $MAX_AGENT_NAME_CHARS символов", "Name is longer than $MAX_AGENT_NAME_CHARS characters"),
        "name",
      )
    }

    if (definition.description.isBlank()) {
      issues += ValidationIssue("blank_description", tr("Описание не может быть пустым", "Description can't be empty"), "description")
    } else if (definition.description.length > MAX_DESCRIPTION_CHARS) {
      issues += ValidationIssue(
        "description_too_long",
        tr("Описание длиннее $MAX_DESCRIPTION_CHARS символов", "Description is longer than $MAX_DESCRIPTION_CHARS characters"),
        "description",
      )
    }

    // The icon is NOT required: an empty iconKey is a legitimate state, `iconFor` returns the default
    // icon. There used to be a blank_icon_key rule here, and it blocked publishing over a decorative
    // field: a working agent could not be built without picking a picture.

    if (definition.systemPrompt.isBlank()) {
      issues += ValidationIssue(
        "blank_system_prompt",
        tr("Системный промпт не может быть пустым", "System prompt can't be empty"),
        "systemPrompt",
      )
    } else if (definition.systemPrompt.length > MAX_SYSTEM_PROMPT_CHARS) {
      issues += ValidationIssue(
        "system_prompt_too_long",
        tr("Промпт длиннее $MAX_SYSTEM_PROMPT_CHARS символов", "Prompt is longer than $MAX_SYSTEM_PROMPT_CHARS characters"),
        "systemPrompt",
      )
    }

    // Only a USER-sourced agent can "collide" with a preset id — a preset's own adapted
    // definition legitimately reuses its own catalog id (that is not identity theft of itself).
    if (definition.source == AgentSource.USER && MiniAgentCatalog.get(definition.id) != null) {
      issues += ValidationIssue(
        "preset_id_collision",
        tr("id '${definition.id}' уже занят пресетом", "id '${definition.id}' is already taken by a template"),
        "id",
      )
    }

    if (definition.defaultMode !in definition.allowedModes) {
      issues += ValidationIssue(
        "default_mode_not_allowed",
        tr("defaultMode должен входить в allowedModes", "The default mode must be one of the allowed modes"),
        "defaultMode",
      )
    }

    val expectedNamespace = "agent:${definition.id}:"
    if (definition.memoryPolicy.namespace != expectedNamespace) {
      issues += ValidationIssue(
        code = "invalid_memory_namespace",
        message = tr(
          "memoryPolicy.namespace должен быть '$expectedNamespace'",
          "memoryPolicy.namespace must be '$expectedNamespace'",
        ),
        field = "memoryPolicy.namespace",
      )
    }

    for (tool in definition.toolNames) {
      if (tool !in KNOWN_TOOL_NAMES) {
        issues += ValidationIssue("unknown_tool", tr("Неизвестный инструмент '$tool'", "Unknown tool '$tool'"), "toolNames")
      }
    }
    for (skill in definition.skillIds) {
      if (skill !in KNOWN_SKILL_IDS && skill !in ownedCustomSkillIds) {
        issues += ValidationIssue("unknown_skill", tr("Неизвестный навык '$skill'", "Unknown skill '$skill'"), "skillIds")
      }
    }
    for (widget in definition.allowedWidgetTemplates) {
      if (widget !in KNOWN_WIDGET_TEMPLATES) {
        issues += ValidationIssue(
          "unknown_widget_template",
          tr("Неизвестный шаблон карточки '$widget'", "Unknown card template '$widget'"),
          "allowedWidgetTemplates",
        )
      }
    }

    // Reuses HookEngine.actionsAllowedFor — the SAME matrix the runtime enforces (Task 8) — so an
    // agent can never publish a hook the engine would just silently skip at runtime (e.g. a DENY
    // declared for BEFORE_TURN, where DENY isn't a valid action at all).
    for (hook in definition.hooks) {
      if (hook.actionType !in HookEngine.actionsAllowedFor(hook.event)) {
        issues += ValidationIssue(
          code = "invalid_hook_action_for_event",
          message = tr(
            "Действие ${hook.actionType} недопустимо для события ${hook.event} (hook '${hook.id}')",
            "Action ${hook.actionType} isn't allowed for event ${hook.event} (hook '${hook.id}')",
          ),
          field = "hooks",
        )
      }
    }

    return issues
  }

  /**
   * Gate [UserAgentRepository.publish] (Task 2) calls before flipping a draft to
   * [AgentStatus.PUBLISHED], so an unpublishable definition can never reach that status regardless
   * of caller. Throws [IllegalArgumentException] listing every [validate] issue rather than just
   * the first, matching [validate]'s own "collect everything" philosophy.
   */
  fun requirePublishable(def: AgentDefinition, ownedCustomSkillIds: Set<String> = emptySet()) {
    val issues = validate(def, ownedCustomSkillIds)
    require(issues.isEmpty()) {
      "Agent '${def.id}' is not publishable: " + issues.joinToString("; ") { "${it.code} (${it.field}): ${it.message}" }
    }
  }
}
