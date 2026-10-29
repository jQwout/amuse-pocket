package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.loop.AgentMode

/**
 * The six pages of the agent builder wizard, in wizard order. [BuilderStep.entries]' ordinal order
 * IS the forward/back navigation order [AgentBuilderViewModel.nextStep]/[AgentBuilderViewModel.previousStep]
 * walk — there is deliberately no separate "step order" list to keep in sync with this declaration.
 */
enum class BuilderStep { IDENTITY, INSTRUCTIONS, CAPABILITIES, LOOP, HOOKS, TEST }

/**
 * Where a draft's content came from when THIS builder session started (Task 23) — pure UI-layer
 * bookkeeping, never persisted onto [AgentDefinition] itself (out of scope; nothing downstream of
 * publish needs it). [MANUAL] is every pre-existing entry point (FAB "create agent", "duplicate"
 * via [AgentBuilderViewModel.cloneFrom], the edit route) and stays the default so none of them
 * change behavior. [ACADEMY_CAPSTONE] is set ONLY by the AI Academy capstone lesson's completion
 * CTA ("Open the builder" on `agent_blueprint`'s `lesson_complete` card) — see
 * `com.aiopenpocket.ui.chat.ChatScreen`'s `onOpenAgentBuilder` wiring. Even then the opened
 * [AgentDraft] is exactly as blank as [AgentDraft.new] always produces: this task deliberately does
 * NOT feed the student's free-form lesson chat through an LLM to prefill any field.
 */
enum class AgentSourceContext { MANUAL, ACADEMY_CAPSTONE }

/**
 * One custom skill being authored in the CAPABILITIES step, before it becomes a `user_skills` row.
 *
 * This exists because the wizard previously threw the whole thing away: its editor collected
 * id/name/description/whenToUse/instructions and passed only the *id* on to [AgentDraft.skillIds].
 * Nothing ever wrote the body, so [com.aiopenpocket.agent.core.agent.AgentValidator] saw an id that
 * was neither a built-in nor an owned custom skill, reported `unknown_skill`, and blocked both
 * "Next" and "Publish" — with no control anywhere able to clear it again.
 */
data class CustomSkillDraft(
  val id: String,
  val name: String = "",
  val description: String = "",
  val whenToUse: String = "",
  val instructions: String = "",
) {
  /** createdAt/updatedAt are 0 on purpose — [com.aiopenpocket.agent.core.agent.UserAgentRepository] stamps them from its own clock. */
  fun toEntity(agentId: String): UserSkillEntity = UserSkillEntity(
    id = id,
    agentId = agentId,
    name = name,
    description = description,
    whenToUse = whenToUse,
    instructionsMarkdown = instructions,
    createdAt = 0L,
    updatedAt = 0L,
  )

  companion object {
    fun fromEntity(entity: UserSkillEntity): CustomSkillDraft = CustomSkillDraft(
      id = entity.id,
      name = entity.name,
      description = entity.description,
      whenToUse = entity.whenToUse,
      instructions = entity.instructionsMarkdown,
    )
  }
}

/**
 * UI-layer, mutable-friendly mirror of [AgentDefinition] that the six wizard steps read and write
 * one field at a time. Neither this type nor a distinct `AgentValidationError` type existed
 * anywhere in `core` before this task (checked against `AgentDefinition.kt`/`AgentValidator.kt`
 * 2026-07-13): [AgentDraft] is the "define it here" branch of the Task 19 brief for the draft type.
 * For the error type, the brief's own fallback applies instead ("reuse the core error type if it
 * exists") — the real domain type is [com.aiopenpocket.agent.core.agent.ValidationIssue]
 * ([com.aiopenpocket.agent.core.agent.AgentValidator.validate]'s actual return element) — so
 * [AgentBuilderUiState.errors] uses that directly rather than inventing a parallel wrapper.
 *
 * Field-for-field this mirrors [AgentDefinition], with deliberate differences that make it safe to
 * hold *invalid, in-progress* input, which a live [AgentDefinition] must never be:
 * - every field has a blank/empty default, so a brand new draft is constructible with zero user
 *   input and only becomes valid once the user fills it in (see [AgentBuilderValidator]);
 * - [source] isn't a field at all — anything this builder writes is always [AgentSource.USER];
 * - [configVersion] is replaced by [lastPublishedConfigVersion], which means something narrower (see
 *   below), not the raw field;
 * - [memoryPolicy] is flattened to just [includeSharedProfile] — [toDefinition] always derives
 *   `namespace = "agent:$id:"` fresh from [id] rather than trusting a stored copy, so that
 *   invariant (which [com.aiopenpocket.agent.core.agent.AgentValidator] enforces strictly) can never
 *   drift out of sync with [id];
 * - [customSkills] carries what [AgentDefinition] structurally cannot: a custom skill's actual
 *   CONTENT. [AgentDefinition.skillIds] is only ids, and the skill bodies live in their own
 *   `user_skills` rows — so the draft has to hold them until a save writes them.
 *
 * [configVersion] is display/bookkeeping only — the stored row's version is owned by the repository
 * ([com.aiopenpocket.agent.core.agent.UserAgentRepository.saveDraft] preserves it, `publish` bumps
 * it), so whatever this field holds is never what gets persisted.
 */
data class AgentDraft(
  val id: String,
  val status: AgentStatus = AgentStatus.DRAFT,
  val configVersion: Long = 0L,
  val name: String = "",
  val description: String = "",
  val iconKey: String = "",
  val systemPrompt: String = "",
  val toolNames: Set<String> = emptySet(),
  val skillIds: Set<String> = emptySet(),
  val defaultMode: AgentMode = AgentMode.CHAT,
  val allowedModes: Set<AgentMode> = setOf(AgentMode.CHAT),
  val includeSharedProfile: Boolean = false,
  val allowedWidgetTemplates: Set<String> = emptySet(),
  val hooks: List<AgentHook> = emptyList(),
  /** This agent's own authored skills — see the class KDoc. Their ids are mirrored into [skillIds]. */
  val customSkills: List<CustomSkillDraft> = emptyList(),
  /**
   * Carried through untouched — the wizard has no control for it (only a clone of a preset that uses
   * state injection ever has a non-empty set). It lives here purely so the round trip through
   * [toDefinition] preserves it: while the draft did NOT carry it, the first autosave after opening
   * a cloned agent rewrote the stored row with an empty set, silently breaking the copy.
   */
  val stateKeyPrefixes: Set<String> = emptySet(),
  /** See [AgentDefinition.model]; null = the app's model. Edited in the LOOP step. */
  val model: String? = null,
  /** See [AgentSourceContext]. Never round-tripped through [toDefinition]/[fromDefinition] — it
   * describes how THIS session's draft was opened, not a property of the published agent. */
  val sourceContext: AgentSourceContext = AgentSourceContext.MANUAL,
) {
  /**
   * Builds the immutable runtime snapshot this draft currently represents — what
   * [AgentBuilderValidator]/[com.aiopenpocket.agent.core.agent.AgentValidator] validate, and what
   * [AgentBuilderViewModel] hands to [com.aiopenpocket.agent.core.agent.UserAgentRepository]. Always
   * [AgentSource.USER] — this builder never edits presets in place. [memoryPolicy]'s namespace is
   * always freshly derived from [id] rather than trusted from stored state (see the class KDoc).
   */
  fun toDefinition(): AgentDefinition = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = name,
    description = description,
    iconKey = iconKey,
    systemPrompt = systemPrompt,
    toolNames = toolNames,
    skillIds = skillIds,
    defaultMode = defaultMode,
    allowedModes = allowedModes,
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = includeSharedProfile),
    allowedWidgetTemplates = allowedWidgetTemplates,
    hooks = hooks,
    stateKeyPrefixes = stateKeyPrefixes,
    model = model,
    configVersion = configVersion,
    status = status,
  )

  /** [customSkills] as the Room rows a save writes. Timestamps are stamped by the repository, not here. */
  fun toSkillEntities(): List<UserSkillEntity> = customSkills.map { it.toEntity(agentId = id) }

  companion object {
    /** A brand-new, empty draft under a freshly generated [id] — see [AgentBuilderViewModel.newDraft].
     * [sourceContext] defaults to [AgentSourceContext.MANUAL]; the capstone completion CTA is the
     * only caller (via [AgentBuilderViewModel.newDraft]) that passes [AgentSourceContext.ACADEMY_CAPSTONE]. */
    fun new(id: String, sourceContext: AgentSourceContext = AgentSourceContext.MANUAL): AgentDraft =
      AgentDraft(id = id, sourceContext = sourceContext)

    /**
     * Mirrors an existing (draft/published/archived) [AgentDefinition] into an editable [AgentDraft].
     * [customSkills] comes from Room separately (see [AgentBuilderViewModel.loadExisting]) — an
     * [AgentDefinition] only carries skill ids, never their bodies.
     */
    fun fromDefinition(definition: AgentDefinition, customSkills: List<CustomSkillDraft> = emptyList()): AgentDraft = AgentDraft(
      id = definition.id,
      status = definition.status,
      configVersion = definition.configVersion,
      customSkills = customSkills,
      name = definition.name,
      description = definition.description,
      iconKey = definition.iconKey,
      systemPrompt = definition.systemPrompt,
      toolNames = definition.toolNames,
      skillIds = definition.skillIds,
      defaultMode = definition.defaultMode,
      allowedModes = definition.allowedModes,
      includeSharedProfile = definition.memoryPolicy.includeSharedProfile,
      allowedWidgetTemplates = definition.allowedWidgetTemplates,
      hooks = definition.hooks,
      stateKeyPrefixes = definition.stateKeyPrefixes,
      model = definition.model,
    )
  }
}

/**
 * Full builder screen state — one instance drives all six [BuilderStep]s (Task 20 renders each as a
 * focused composable reading the same [draft]/[errors]).
 *
 * @property errors Every issue [com.aiopenpocket.agent.core.agent.AgentValidator.validate] currently
 *   reports for [draft] — the FULL set across all six steps, not sliced to the current [step]; use
 *   [AgentBuilderValidator.errorsForStep] to slice it for display.
 * @property isSaving True while an autosave, publish, or archive repository call is in flight.
 * @property canPublish [errors] is empty (see [AgentBuilderValidator.canPublish]).
 * @property isLoading True until the initial [AgentBuilderViewModel.newDraft]/`loadExisting`/
 *   `cloneFrom` call finishes populating [draft] from the repository. [draft] holds an empty
 *   placeholder ([AgentDraft.new] with a blank id) until then.
 * @property saveError Human-readable message from the most recent FAILED autosave/publish/archive
 *   call, or null. Per the Task 19 brief: a save error "stays in state and must NOT drop the
 *   in-memory draft" — [draft] is left exactly as the user last edited it when this is set.
 *
 * [isLoading] and [saveError] are not in the Task 19 brief's own state sketch (`draft`/`step`/
 * `errors`/`isSaving`/`canPublish`/`executionMode` only) — they are added here because without them
 * there is nowhere to represent "the draft hasn't loaded yet" (needed so Task 20 doesn't render/
 * autosave a placeholder) or "the last autosave failed" (explicitly required by the brief's autosave
 * step, quoted above). Flagged in the Task 19 report for visibility.
 */
data class AgentBuilderUiState(
  val draft: AgentDraft,
  val step: BuilderStep = BuilderStep.IDENTITY,
  val errors: List<ValidationIssue> = emptyList(),
  /**
   * Whether to show the inline errors of the editable steps. Validation happens "on the button": on a
   * fresh draft all fields are empty, and the screen used to greet the user with three red lines right
   * away, although they had done nothing yet. [AgentBuilderViewModel.nextStep] raises the flag when
   * "Next" was tapped with blocking errors, and any step change resets it. The "Check" step is NOT
   * driven by this flag — there the summary of blockers is the point of the screen.
   */
  val showErrors: Boolean = false,
  val isSaving: Boolean = false,
  val canPublish: Boolean = false,
  // PREVIEW, not LIVE: the first test run of a draft must not really send emails/write to the calendar.
  // The field used to default to LIVE, with PREVIEW forced by a LaunchedEffect in the route — a prop
  // that held only because the screen was always opened through that particular route.
  val executionMode: ExecutionMode = ExecutionMode.PREVIEW,
  val isLoading: Boolean = true,
  val saveError: String? = null,
  /**
   * "Publish" has just succeeded. Success used to go unmarked: the button simply stopped spinning, and it
   * was unclear whether the agent had gone to the roster and what to do next. Raised by
   * [AgentBuilderViewModel.publish], cleared by any following edit ([AgentBuilderViewModel.updateDraft]).
   */
  val justPublished: Boolean = false,
)
