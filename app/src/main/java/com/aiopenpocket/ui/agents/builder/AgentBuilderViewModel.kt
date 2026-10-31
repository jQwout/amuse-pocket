package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.localizedSystemPrompt
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.util.UUID
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Logic layer for the six-step agent builder wizard (Task 20 supplies the Compose UI over this
 * state — no UI here). Owns: draft state for all six [BuilderStep]s, debounced autosave, per-step
 * validation gating (via [AgentBuilderValidator]), clone-from-preset-or-agent, publish, archive, and
 * restoration after recreation (every load re-reads [userAgentRepository]/[agentResolver], never an
 * in-memory cache from a prior instance).
 *
 * Constructor dependencies match the Task 19 brief's consumed interfaces exactly: `AgentValidator` is
 * used indirectly through [AgentBuilderValidator] (never called from here directly), `ExecutionMode`
 * has no separate binding (it is a plain enum carried in [AgentBuilderUiState.executionMode]).
 */
@OptIn(FlowPreview::class)
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AgentBuilderViewModel(
  private val userAgentRepository: UserAgentRepository,
  private val agentResolver: AgentResolver,
  private val skillRegistry: SkillRegistry,
  private val userSkillRepository: UserSkillRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(AgentBuilderUiState(draft = AgentDraft.new(id = "")))
  val state: StateFlow<AgentBuilderUiState> = _state.asStateFlow()

  /**
   * Built-in skill ids the CAPABILITIES step offers as tickable chips. Deliberately NOT
   * [SkillRegistry.ids] (which is app-wide): another agent's custom skill can never be ticked here,
   * because `publish` refuses to reassign it. The agent's own custom skills come from the draft.
   */
  private val _availableSkillIds = MutableStateFlow<Set<String>>(emptySet())
  val availableSkillIds: StateFlow<Set<String>> = _availableSkillIds.asStateFlow()

  /**
   * Fires ONLY from [updateDraft] — the sole autosave trigger source. Deliberately separate from
   * [_state] itself: loading a draft (new/existing/clone) never writes here, so merely OPENING an
   * already-PUBLISHED agent to look at it can never autosave it — [saveDraft][UserAgentRepository.saveDraft]
   * would immediately flip it to DRAFT status (dropping it out of the roster) even though the user
   * never changed anything. The brief is explicit that autosave is about "draft edits", not loads.
   *
   * [applyLoaded] sets this back to `null` when switching agents specifically so a pending edit on
   * the PREVIOUS agent never lands after the switch. That only works because [debounce] below runs
   * BEFORE [filterNotNull]: debounce resets its window on every emission it sees, including this
   * `null`, so the previous non-null edit's timer never fires on its own; filtering afterward then
   * just drops the (debounced) `null` itself instead of persisting it. The other order — filtering
   * nulls out first — would hide this `null` from debounce entirely, leaving the earlier edit's
   * timer running untouched and persisting stale data after the switch.
   */
  private val pendingEdits = MutableStateFlow<AgentDraft?>(null)

  /**
   * The arguments [start] has already run with. The route calls it from a `LaunchedEffect`, which
   * restarts on every recreation of the composition — a screen rotation, a theme change — although the
   * ViewModel itself survives them untouched. Without this mark a rotation started a new empty draft
   * with a new id (and editing threw the user back to the first step), and for every new id the test
   * step created one more "Agent test" chat. The same trick as `AgentDetailViewModel.select`.
   */
  private var startedWith: Triple<String?, AgentSourceContext, Boolean>? = null

  init {
    viewModelScope.launch {
      pendingEdits.debounce(AUTOSAVE_DEBOUNCE_MS).filterNotNull().collect { draft -> persist(draft) }
    }
    _availableSkillIds.value = runCatching { skillRegistry.builtInIds() }.getOrDefault(emptySet())
  }

  // --- Entry points (Task 20 route wiring calls exactly one of these per navigation entry) -------

  /**
   * Convenience for the two builder routes: null [agentId] starts [newDraft], otherwise
   * [loadExisting]. Call once per navigation entry (e.g. from a Compose `LaunchedEffect(agentId)`),
   * not on every recomposition. [sourceContext] (Task 23) only matters for the null-[agentId]
   * branch — see [newDraft]; it is ignored (never overrides an existing agent's bookkeeping) when
   * [loadExisting] runs instead. [openReview] (the `review=true` route argument) opens an EXISTING draft on
   * the review screen; a brand-new empty draft has nothing to review, so it is ignored for a null [agentId].
   *
   * A repeated call with the same arguments is a no-op (see [startedWith]): the draft and the current
   * step survive a screen rotation. [newDraft]/[loadExisting] themselves are not guarded — an explicit
   * call still reloads.
   */
  fun start(agentId: String?, sourceContext: AgentSourceContext = AgentSourceContext.MANUAL, openReview: Boolean = false) {
    val key = Triple(agentId, sourceContext, openReview)
    if (startedWith == key) return
    startedWith = key
    if (agentId == null) newDraft(sourceContext) else loadExisting(agentId, openReview)
  }

  /**
   * Starts a brand-new, empty USER draft under a fresh generated id (`agent_builder`, no id).
   * [sourceContext] defaults to [AgentSourceContext.MANUAL] (FAB "create agent" and every other
   * pre-existing entry point); the AI Academy capstone lesson's completion CTA (Task 23) is the only
   * caller that passes [AgentSourceContext.ACADEMY_CAPSTONE]. Either way the draft itself is exactly
   * as blank as [AgentDraft.new] always produces — this is bookkeeping only, never a prefill.
   */
  fun newDraft(sourceContext: AgentSourceContext = AgentSourceContext.MANUAL) {
    applyLoaded(AgentDraft.new(id = generateDraftId(), sourceContext = sourceContext))
  }

  /**
   * (Re)loads [agentId] from the repository — the edit route (`agent_builder/{agentId}`), and also
   * the restoration path: a freshly recreated ViewModel calling this again always re-reads the
   * CURRENT repository row rather than trusting any other instance's in-memory state, so whatever
   * the previous instance's autosave last persisted is exactly what reappears.
   */
  fun loadExisting(agentId: String, openReview: Boolean = false) {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true) }
      try {
        val resolved = agentResolver.resolve(agentId)
        applyLoaded(
          resolved?.let { AgentDraft.fromDefinition(it, customSkillsOf(agentId)) } ?: AgentDraft.new(agentId),
          review = openReview,
        )
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, saveError = ERROR_LOAD) }
      }
    }
  }

  /** This agent's own authored skill bodies — [AgentDefinition] carries only their ids. */
  private suspend fun customSkillsOf(agentId: String): List<CustomSkillDraft> =
    userSkillRepository.getForAgent(agentId).map(CustomSkillDraft::fromEntity)

  /**
   * Forks [sourceAgentId] (a preset OR an existing user agent — [AgentResolver.resolve] covers
   * both) into a brand-new USER draft: fresh id/memory namespace via
   * [UserAgentRepository.clonePreset], renamed "Copy: <name>" per the brief — the repository method
   * itself does not rename, Task 19 owns that. The rename is persisted immediately (not left to the
   * debounced autosave) so the stored row already matches what is on screen even if the process
   * dies before the user makes their first real edit.
   */
  fun cloneFrom(sourceAgentId: String) {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true) }
      try {
        val source = agentResolver.resolve(sourceAgentId)
        if (source == null) {
          _state.update { it.copy(isLoading = false, saveError = ERROR_NOT_FOUND) }
          return@launch
        }
        val cloned = userAgentRepository.clonePreset(source)
        val clonedSkills = customSkillsOf(cloned.id)
        // The copy's name and description are in the interface language: a preset has English ones
        // (displayName), the user's own agent keeps the source's. They go into the DB as ordinary author text.
        val renamed = userAgentRepository.saveDraft(
          definition = cloned.copy(
            name = tr("Копия: ${source.displayName}", "Copy: ${source.displayName}"),
            description = source.displayDescription,
            systemPrompt = source.localizedSystemPrompt,
          ),
          skills = clonedSkills.map { it.toEntity(cloned.id) },
          hooks = cloned.hooks,
        )
        applyLoaded(AgentDraft.fromDefinition(renamed, clonedSkills))
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, saveError = ERROR_CLONE) }
      }
    }
  }

  // --- Editing -------------------------------------------------------------------------------------

  /** The one editing entry point every wizard step's field controls call through, e.g. `updateDraft { it.copy(name = text) }`. Re-validates synchronously and queues a debounced autosave. */
  fun updateDraft(transform: (AgentDraft) -> AgentDraft) {
    val updated = transform(_state.value.draft)
    setDraftAndValidate(updated)
    // A new edit after publishing is already an unpublished change: the "Published" badge would
    // otherwise lie that the screen shows the same as what went to the roster.
    _state.update { it.copy(justPublished = false) }
    pendingEdits.value = updated
  }

  /** Toggles the TEST step's run mode between [ExecutionMode.LIVE] and [ExecutionMode.PREVIEW]; Task 20 reads this to decide how it invokes `TurnManager`. */
  fun setExecutionMode(mode: ExecutionMode) {
    _state.update { it.copy(executionMode = mode) }
  }

  // --- Step navigation -------------------------------------------------------------------------------

  fun goToStep(step: BuilderStep) {
    _state.update { it.copy(step = step, showErrors = false) }
  }

  /**
   * Leaves the review screen for the step wizard, on [step] ("Edit step by step" opens the first one, "Edit
   * rules" the hooks). The draft is the same object the review edited, so nothing is lost either way.
   */
  fun editStepByStep(step: BuilderStep = BuilderStep.IDENTITY) {
    _state.update { it.copy(reviewMode = false, step = step, showErrors = false) }
  }

  /**
   * Advances to the next step; returns false (no-op) if the CURRENT step still has its own blocking
   * errors, or [BuilderStep.TEST] is already active.
   *
   * Validation "on the button": a refusal is not silent, it raises [AgentBuilderUiState.showErrors] —
   * only then does the step draw inline errors. Before the first "Next" a fresh draft does not greet
   * the user with red lines on fields they have not touched yet.
   */
  fun nextStep(): Boolean {
    val current = _state.value
    if (!AgentBuilderValidator.canAdvanceFrom(current.step, current.errors)) {
      _state.update { it.copy(showErrors = true) }
      return false
    }
    val next = BuilderStep.entries.getOrNull(current.step.ordinal + 1) ?: return false
    _state.update { it.copy(step = next, showErrors = false) }
    return true
  }

  /** Moves back one step; returns false (no-op) if already on [BuilderStep.IDENTITY]. Never blocked by errors — you can always go back. */
  fun previousStep(): Boolean {
    val current = _state.value
    val prev = BuilderStep.entries.getOrNull(current.step.ordinal - 1) ?: return false
    _state.update { it.copy(step = prev, showErrors = false) }
    return true
  }

  // --- Publish / archive ---------------------------------------------------------------------------

  /**
   * Flushes the latest edits, then publishes — one [UserAgentRepository.publish] call, which bumps
   * the stored `configVersion` by exactly one. (This used to need a catch-up LOOP: `saveDraft` reset
   * the stored version to 0 on every autosave, so a single publish after any edit reported `1`
   * instead of the true next version. `saveDraft` now preserves status/version, so the ratchet is
   * simply correct and the loop is gone.)
   */
  fun publish() {
    viewModelScope.launch {
      val draft = _state.value.draft
      val issues = AgentBuilderValidator.validate(draft)
      if (issues.isNotEmpty()) {
        _state.update { it.copy(errors = issues, canPublish = false) }
        return@launch
      }
      _state.update { it.copy(isSaving = true) }
      try {
        userAgentRepository.saveDraft(draft.toDefinition(), skills = draft.toSkillEntities(), hooks = draft.hooks)
        val published = userAgentRepository.publish(
          id = draft.id,
          skills = draft.toSkillEntities(),
          hooks = draft.hooks,
        )
        _state.update {
          it.copy(
            isSaving = false,
            saveError = null,
            justPublished = true,
            draft = it.draft.copy(status = published.status, configVersion = published.configVersion),
          )
        }
      } catch (e: Exception) {
        // The exception text is not shown: these are the repository's `require` messages in English, with
        // codes and ids. The local validation above has already filtered out everything fixable on the steps.
        _state.update { it.copy(isSaving = false, saveError = ERROR_PUBLISH) }
      }
    }
  }

  /** Archives the current agent — the row survives (status only), matching [UserAgentRepository.archive]. */
  fun archive() {
    viewModelScope.launch {
      val id = _state.value.draft.id
      _state.update { it.copy(isSaving = true) }
      try {
        userAgentRepository.archive(id)
        _state.update {
          it.copy(isSaving = false, saveError = null, draft = it.draft.copy(status = AgentStatus.ARCHIVED))
        }
      } catch (e: Exception) {
        _state.update { it.copy(isSaving = false, saveError = ERROR_ARCHIVE) }
      }
    }
  }

  // --- Internals -------------------------------------------------------------------------------------

  private fun applyLoaded(draft: AgentDraft, review: Boolean = false) {
    pendingEdits.value = null // discard any stale pending edit left over from whatever was loaded before
    setDraftAndValidate(draft)
    _state.update {
      it.copy(
        isLoading = false,
        step = BuilderStep.IDENTITY,
        reviewMode = review,
        isSaving = false,
        saveError = null,
        justPublished = false,
      )
    }
  }

  private fun setDraftAndValidate(draft: AgentDraft) {
    val issues = AgentBuilderValidator.validate(draft)
    _state.update { it.copy(draft = draft, errors = issues, canPublish = AgentBuilderValidator.canPublish(issues)) }
  }

  private suspend fun persist(draft: AgentDraft) {
    _state.update { it.copy(isSaving = true) }
    try {
      // Hooks and custom skills travel WITH the agent row: authored in the wizard, they used to live
      // only in memory until publish, so closing the wizard first silently discarded them.
      userAgentRepository.saveDraft(draft.toDefinition(), skills = draft.toSkillEntities(), hooks = draft.hooks)
      _state.update { current ->
        // saveDraft no longer rewrites status/configVersion, so there is nothing to mirror back here:
        // an edited PUBLISHED agent stays published (and stays in the roster) until publish() bumps it.
        current.copy(isSaving = false, saveError = null)
      }
    } catch (e: Exception) {
      // Brief: "a save error stays in state and must NOT drop the in-memory draft" - current.draft
      // (whatever the user has typed since this failed save started) is left completely untouched.
      _state.update { it.copy(isSaving = false, saveError = ERROR_SAVE) }
    }
  }

  /** Generates a fresh id for [newDraft] that satisfies `AgentValidator`'s id shape (lowercase alnum start, then lowercase alnum/hyphen/underscore, 3-64 chars) without referencing its internal `ID_REGEX` directly, per the Task 19 brief. `AgentBuilderValidator.validate` remains the actual source of truth if this ever drifts. */
  private fun generateDraftId(): String =
    "agent-${UUID.randomUUID().toString().replace("-", "").take(ID_SUFFIX_LENGTH)}"

  companion object {
    const val AUTOSAVE_DEBOUNCE_MS = 500L
    private const val ID_SUFFIX_LENGTH = 10

    // Error texts for the user are in the interface language and without `e.message`: that gets English
    // `require`/Room messages with codes and ids that explain nothing on screen.
    // Getters, not consts: the language is chosen at the moment of the error, not when the class loads.
    private val ERROR_LOAD: String
      get() = tr("Не удалось загрузить агента. Попробуй открыть его ещё раз.", "Couldn't load the agent. Try opening it again.")
    private val ERROR_NOT_FOUND: String get() = tr("Агент не найден.", "Agent not found.")
    private val ERROR_CLONE: String get() = tr("Не удалось создать копию агента.", "Couldn't duplicate the agent.")
    private val ERROR_PUBLISH: String
      get() = tr("Не удалось опубликовать агента. Попробуй ещё раз.", "Couldn't publish the agent. Try again.")
    private val ERROR_ARCHIVE: String get() = tr("Не удалось убрать агента в архив.", "Couldn't archive the agent.")
    private val ERROR_SAVE: String
      get() = tr(
        "Не удалось сохранить черновик — правки пока только на экране.",
        "Couldn't save the draft — your changes are only on screen for now.",
      )
  }
}
