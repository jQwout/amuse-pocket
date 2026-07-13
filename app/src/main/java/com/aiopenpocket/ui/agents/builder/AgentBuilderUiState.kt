package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.policy.ExecutionMode
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
 * CTA ("Собрать этого агента" on `agent_blueprint`'s `lesson_complete` card) — see
 * `com.aiopenpocket.ui.chat.ChatScreen`'s `onOpenAgentBuilder` wiring. Even then the opened
 * [AgentDraft] is exactly as blank as [AgentDraft.new] always produces: this task deliberately does
 * NOT feed the student's free-form lesson chat through an LLM to prefill any field.
 */
enum class AgentSourceContext { MANUAL, ACADEMY_CAPSTONE }

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
 *   drift out of sync with [id].
 *
 * ### [lastPublishedConfigVersion] — carrying the published version across an edit
 *
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository.saveDraft] unconditionally persists
 * `status = DRAFT, configVersion = 0` on EVERY autosave, regardless of what the row looked like
 * before (by its own design — see its KDoc: "regardless of the status/configVersion the caller
 * passed in"). [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish] then bumps
 * configVersion by exactly one from whatever is *currently stored*. Chained together, editing an
 * already-PUBLISHED agent (autosave fires on every debounced edit) resets the stored configVersion
 * to 0 before the user ever clicks "Publish" again — so a single, naive `repository.publish(id)`
 * call after any edit always reports `1`, never the true next version. This is the Task 19 brief's
 * flagged CRITICAL correctness rule: publishing an edited agent must be monotonic (never
 * reuse/decrement). The repository's own transaction semantics — verified against
 * `UserAgentRepositoryTest` and matching the original Task 2 plan's own pseudocode
 * (`configVersion = draft.configVersion + 1`) — do not guarantee that once even one autosave has run
 * in between, so this is handled here instead of by changing that repository (out of this task's
 * scope, and its existing tests rely on the simpler current contract).
 *
 * [lastPublishedConfigVersion] is populated once, at load time
 * ([AgentDraft.fromDefinition]), from whatever [AgentDefinition.configVersion] this agent had
 * BEFORE this editing session began (0 for a never-published agent — [saveDraft] having already
 * forced any unpublished row to 0 itself, so this is never a lossy read), and is carried in memory
 * across every subsequent autosave (autosave never touches this field — see
 * [AgentBuilderViewModel]). At publish time the target version is `lastPublishedConfigVersion + 1`;
 * see [AgentBuilderViewModel.publish] for how that target is actually reached.
 */
data class AgentDraft(
  val id: String,
  val status: AgentStatus = AgentStatus.DRAFT,
  val lastPublishedConfigVersion: Long = 0L,
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
    configVersion = lastPublishedConfigVersion,
    status = status,
  )

  companion object {
    /** A brand-new, empty draft under a freshly generated [id] — see [AgentBuilderViewModel.newDraft].
     * [sourceContext] defaults to [AgentSourceContext.MANUAL]; the capstone completion CTA is the
     * only caller (via [AgentBuilderViewModel.newDraft]) that passes [AgentSourceContext.ACADEMY_CAPSTONE]. */
    fun new(id: String, sourceContext: AgentSourceContext = AgentSourceContext.MANUAL): AgentDraft =
      AgentDraft(id = id, sourceContext = sourceContext)

    /** Mirrors an existing (draft/published/archived) [AgentDefinition] into an editable [AgentDraft]. */
    fun fromDefinition(definition: AgentDefinition): AgentDraft = AgentDraft(
      id = definition.id,
      status = definition.status,
      lastPublishedConfigVersion = definition.configVersion,
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
  val isSaving: Boolean = false,
  val canPublish: Boolean = false,
  val executionMode: ExecutionMode = ExecutionMode.LIVE,
  val isLoading: Boolean = true,
  val saveError: String? = null,
)
