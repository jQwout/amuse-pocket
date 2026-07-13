package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.di.AppScope
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
@ViewModelKey(AgentBuilderViewModel::class)
class AgentBuilderViewModel(
  private val userAgentRepository: UserAgentRepository,
  private val agentResolver: AgentResolver,
  private val skillRegistry: SkillRegistry,
) : ViewModel() {

  private val _state = MutableStateFlow(AgentBuilderUiState(draft = AgentDraft.new(id = "")))
  val state: StateFlow<AgentBuilderUiState> = _state.asStateFlow()

  /** Every skill id the CAPABILITIES step can offer to pick from (built-in + already-authored custom, app-wide). */
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

  init {
    viewModelScope.launch {
      pendingEdits.debounce(AUTOSAVE_DEBOUNCE_MS).filterNotNull().collect { draft -> persist(draft) }
    }
    viewModelScope.launch {
      _availableSkillIds.value = runCatching { skillRegistry.ids() }.getOrDefault(emptySet())
    }
  }

  // --- Entry points (Task 20 route wiring calls exactly one of these per navigation entry) -------

  /** Convenience for the two builder routes: null [agentId] starts [newDraft], otherwise [loadExisting]. Call once per navigation entry (e.g. from a Compose `LaunchedEffect(agentId)`), not on every recomposition. */
  fun start(agentId: String?) {
    if (agentId == null) newDraft() else loadExisting(agentId)
  }

  /** Starts a brand-new, empty USER draft under a fresh generated id (`agent_builder`, no id). */
  fun newDraft() {
    applyLoaded(AgentDraft.new(id = generateDraftId()))
  }

  /**
   * (Re)loads [agentId] from the repository — the edit route (`agent_builder/{agentId}`), and also
   * the restoration path: a freshly recreated ViewModel calling this again always re-reads the
   * CURRENT repository row rather than trusting any other instance's in-memory state, so whatever
   * the previous instance's autosave last persisted is exactly what reappears.
   */
  fun loadExisting(agentId: String) {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true) }
      try {
        val resolved = agentResolver.resolve(agentId)
        applyLoaded(resolved?.let(AgentDraft::fromDefinition) ?: AgentDraft.new(agentId))
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, saveError = e.message ?: "Load failed") }
      }
    }
  }

  /**
   * Forks [sourceAgentId] (a preset OR an existing user agent — [AgentResolver.resolve] covers
   * both) into a brand-new USER draft: fresh id/memory namespace via
   * [UserAgentRepository.clonePreset], renamed "Копия: <имя>" per the brief — the repository method
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
          _state.update { it.copy(isLoading = false, saveError = "Агент '$sourceAgentId' не найден") }
          return@launch
        }
        val cloned = userAgentRepository.clonePreset(source)
        val renamed = userAgentRepository.saveDraft(cloned.copy(name = "Копия: ${source.name}"))
        applyLoaded(AgentDraft.fromDefinition(renamed))
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, saveError = e.message ?: "Clone failed") }
      }
    }
  }

  // --- Editing -------------------------------------------------------------------------------------

  /** The one editing entry point every wizard step's field controls call through, e.g. `updateDraft { it.copy(name = text) }`. Re-validates synchronously and queues a debounced autosave. */
  fun updateDraft(transform: (AgentDraft) -> AgentDraft) {
    val updated = transform(_state.value.draft)
    setDraftAndValidate(updated)
    pendingEdits.value = updated
  }

  /** Toggles the TEST step's run mode between [ExecutionMode.LIVE] and [ExecutionMode.PREVIEW]; Task 20 reads this to decide how it invokes `TurnManager`. */
  fun setExecutionMode(mode: ExecutionMode) {
    _state.update { it.copy(executionMode = mode) }
  }

  // --- Step navigation -------------------------------------------------------------------------------

  fun goToStep(step: BuilderStep) {
    _state.update { it.copy(step = step) }
  }

  /** Advances to the next step; returns false (no-op) if the CURRENT step still has its own blocking errors, or [BuilderStep.TEST] is already active. */
  fun nextStep(): Boolean {
    val current = _state.value
    if (!AgentBuilderValidator.canAdvanceFrom(current.step, current.errors)) return false
    val next = BuilderStep.entries.getOrNull(current.step.ordinal + 1) ?: return false
    _state.update { it.copy(step = next) }
    return true
  }

  /** Moves back one step; returns false (no-op) if already on [BuilderStep.IDENTITY]. Never blocked by errors — you can always go back. */
  fun previousStep(): Boolean {
    val current = _state.value
    val prev = BuilderStep.entries.getOrNull(current.step.ordinal - 1) ?: return false
    _state.update { it.copy(step = prev) }
    return true
  }

  // --- Publish / archive ---------------------------------------------------------------------------

  /**
   * Validates then publishes the current draft. See [AgentDraft]'s KDoc ("carrying the published
   * configVersion") for the full explanation of why this cannot be a single
   * [UserAgentRepository.publish] call for an already-published agent: the debounced autosave that
   * ran while the user typed has already reset the STORED configVersion to 0 (`saveDraft`'s own
   * contract), so the repository's own "current stored value + 1" computation would report `1` — a
   * regression for any agent published more than once before.
   *
   * Fix, without touching that repository (out of this task's scope): flush the latest edits, then
   * call `publish` in a loop — idempotent, same id/skills/hooks every time — until the stored
   * configVersion reaches [AgentDraft.lastPublishedConfigVersion] + 1, closing exactly the gap the
   * autosave reset opened. For the common case (never published before, or nothing edited since the
   * last publish) this is exactly one call, identical to calling the repository directly.
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
        userAgentRepository.saveDraft(draft.toDefinition())
        val target = draft.lastPublishedConfigVersion + 1
        val published = publishUntil(draft.id, draft.hooks, target)
        _state.update {
          it.copy(
            isSaving = false,
            saveError = null,
            draft = it.draft.copy(status = published.status, lastPublishedConfigVersion = published.configVersion),
          )
        }
      } catch (e: Exception) {
        _state.update { it.copy(isSaving = false, saveError = e.message ?: "Publish failed") }
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
        _state.update { it.copy(isSaving = false, saveError = e.message ?: "Archive failed") }
      }
    }
  }

  // --- Internals -------------------------------------------------------------------------------------

  /** Calls [UserAgentRepository.publish] repeatedly (it only ever ratchets stored configVersion by +1 per call) until it reaches [targetVersion]. See [publish]'s KDoc for why this is necessary. [MAX_PUBLISH_CATCH_UP] is a defensive ceiling only — [targetVersion] is always this agent's own real publish count plus one, never attacker- or user-controlled. */
  private suspend fun publishUntil(id: String, hooks: List<AgentHook>, targetVersion: Long): AgentDefinition {
    var published = userAgentRepository.publish(id, hooks = hooks)
    var attempts = 1
    while (published.configVersion < targetVersion) {
      check(attempts < MAX_PUBLISH_CATCH_UP) {
        "configVersion did not converge to $targetVersion for '$id' after $attempts publish() calls"
      }
      published = userAgentRepository.publish(id, hooks = hooks)
      attempts += 1
    }
    return published
  }

  private fun applyLoaded(draft: AgentDraft) {
    pendingEdits.value = null // discard any stale pending edit left over from whatever was loaded before
    setDraftAndValidate(draft)
    _state.update { it.copy(isLoading = false, step = BuilderStep.IDENTITY, isSaving = false, saveError = null) }
  }

  private fun setDraftAndValidate(draft: AgentDraft) {
    val issues = AgentBuilderValidator.validate(draft)
    _state.update { it.copy(draft = draft, errors = issues, canPublish = AgentBuilderValidator.canPublish(issues)) }
  }

  private suspend fun persist(draft: AgentDraft) {
    _state.update { it.copy(isSaving = true) }
    try {
      userAgentRepository.saveDraft(draft.toDefinition())
      _state.update { current ->
        current.copy(
          isSaving = false,
          saveError = null,
          // saveDraft always forces the stored row to DRAFT - reflect that here too, but leave
          // lastPublishedConfigVersion untouched: that's carried history, never reset by autosave.
          draft = if (current.draft.id == draft.id) current.draft.copy(status = AgentStatus.DRAFT) else current.draft,
        )
      }
    } catch (e: Exception) {
      // Brief: "a save error stays in state and must NOT drop the in-memory draft" - current.draft
      // (whatever the user has typed since this failed save started) is left completely untouched.
      _state.update { it.copy(isSaving = false, saveError = e.message ?: "Save failed") }
    }
  }

  /** Generates a fresh id for [newDraft] that satisfies `AgentValidator`'s id shape (lowercase alnum start, then lowercase alnum/hyphen/underscore, 3-64 chars) without referencing its internal `ID_REGEX` directly, per the Task 19 brief. `AgentBuilderValidator.validate` remains the actual source of truth if this ever drifts. */
  private fun generateDraftId(): String =
    "agent-${UUID.randomUUID().toString().replace("-", "").take(ID_SUFFIX_LENGTH)}"

  companion object {
    const val AUTOSAVE_DEBOUNCE_MS = 500L
    private const val ID_SUFFIX_LENGTH = 10
    private const val MAX_PUBLISH_CATCH_UP = 10_000
  }
}
