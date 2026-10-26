package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.data.repository.AgentFilesRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One resolved row for the Tools section — real tool metadata, never a bare count. */
data class AgentDetailToolRow(
  val name: String,
  val description: String,
  val riskClass: RiskClass?,
  val available: Boolean,
)

/** One resolved row for the Skills section — real skill-pack metadata, never "yes/no". */
data class AgentDetailSkillRow(
  val id: String,
  /**
   * What the user sees instead of [id]: the name of the agent's own skill from Room or the built-in
   * one's name from [CapabilityNames.skill]. The [id] itself is a contract with the model
   * (`load_skill(id)`) and never goes to the screen.
   */
  val title: String,
  val description: String,
  val whenToUse: String,
  val available: Boolean,
)

/** One hook plus its most recent journaled firings (Task 8's `agent_hook_runs`, capped for display). */
data class AgentDetailHookRow(
  val hook: AgentHook,
  val recentRuns: List<AgentHookRunEntity>,
)

/** One resolved row for the Plans section (Task 12) — a flattened, display-ready projection of one [PlanSnapshot] for this agent. */
data class AgentDetailPlanRow(
  val planId: String,
  val objective: String,
  val status: PlanStatus,
  val stepsCompleted: Int,
  val stepsTotal: Int,
  val updatedAt: Long,
)

data class AgentDetailState(
  val isLoading: Boolean = true,
  val definition: AgentDefinition? = null,
  val tools: List<AgentDetailToolRow> = emptyList(),
  val skills: List<AgentDetailSkillRow> = emptyList(),
  val hooks: List<AgentDetailHookRow> = emptyList(),
  val activeHeartbeats: List<HeartbeatEntity> = emptyList(),
  val files: List<AgentFile> = emptyList(),
  /** Every plan ever proposed for this agent (Task 12, `PlanRepository.observeByAgent`) — sorted newest-updated first. Empty for a preset with no plans, honestly (see `AgentDetailScreen`'s empty-state card). */
  val plans: List<AgentDetailPlanRow> = emptyList(),
  /**
   * True when [tools]/[skills] contains a row the CURRENT build no longer registers (e.g. the
   * definition was authored against a newer build, then the APK was downgraded). The rest of the
   * screen still renders normally — this only drives a non-blocking warning banner, never a crash.
   */
  val hasInvalidCapabilities: Boolean = false,
  /** Message from the most recently FAILED action (duplicate/toggle/archive/reload), or null. */
  val actionError: String? = null,
)

/** [AgentSource.USER] only — presets are read-only Kotlin and can never be edited in place. */
val AgentDetailState.canEdit: Boolean get() = definition?.source == AgentSource.USER

/** Any resolved agent (preset or user) can be forked into a new, independently editable draft. */
val AgentDetailState.canDuplicate: Boolean get() = definition != null

/** [AgentSource.USER] only — presets have no lifecycle to disable/re-enable. */
val AgentDetailState.canToggleEnabled: Boolean get() = definition?.source == AgentSource.USER

/** [AgentSource.USER] only, and only while not already archived (nothing further to archive). */
val AgentDetailState.canArchive: Boolean
  get() {
    val def = definition ?: return false
    return def.source == AgentSource.USER && def.status != AgentStatus.ARCHIVED
  }

/** True while [AgentDetailState.definition] is currently live in the roster ([AgentStatus.PUBLISHED]). */
val AgentDetailState.isEnabled: Boolean get() = definition?.status == AgentStatus.PUBLISHED

private val TERMINAL_PLAN_STATUSES = setOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)

/** Plans section counts (Task 12) — every non-terminal, non-paused status (including a pending proposal) counts as "active", mirroring `PlanListState`'s own three-bucket split. */
val AgentDetailState.activePlansCount: Int
  get() = plans.count { it.status !in TERMINAL_PLAN_STATUSES && it.status != PlanStatus.PAUSED }
val AgentDetailState.pausedPlansCount: Int get() = plans.count { it.status == PlanStatus.PAUSED }
val AgentDetailState.completedPlansCount: Int get() = plans.count { it.status in TERMINAL_PLAN_STATUSES }

/**
 * Full-screen agent detail (Task 21) — supersedes the deleted glass bottom-sheet inspector
 * (`com.aiopenpocket.ui.agents.AgentInspectorViewModel`/`AgentInspectorSheet`) with a real screen
 * whose state aggregates everything the old sheet showed, PLUS the real data it used to fake or
 * omit: resolved tool rows (name+description+riskClass, via [ToolRegistry]), resolved skill rows
 * (via the composite [SkillRegistry]), the agent's REAL hooks plus their recent firing journal
 * ([AgentHookRepository], Task 8 — replacing the sheet's fake on_tool_call/pre_message stubs),
 * active heartbeats (as the old inspector), and this agent's own sandbox files
 * ([AgentFilesRepository], Task 6).
 *
 * agentId arrives AFTER construction via [select] — the same pattern the old inspector and
 * `ChatViewModel.setConversationId` already use: a `metroViewModel()`-obtained instance cannot take
 * runtime navigation arguments through its constructor.
 *
 * A tool/skill id in [AgentDefinition.toolNames]/[AgentDefinition.skillIds] that no registry
 * recognizes (e.g. authored against a newer build, then the APK was downgraded) never crashes
 * resolution — [resolveToolRow]/[resolveSkillRow] fail closed into an "unavailable" row instead, and
 * [AgentDetailState.hasInvalidCapabilities] flips true so the screen can warn without blocking.
 *
 * [AgentDetailState.plans] (Task 12) is, like [AgentDetailState.activeHeartbeats], a LIVE
 * subscription independent of [reload] — [PlanRepository.observeByAgent] rather than a one-shot
 * resolver call, so approving/pausing/resuming/cancelling a plan (from chat or `plan/{planId}`)
 * updates this screen's Plans section without navigating back to it.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AgentDetailViewModel(
  private val agentResolver: AgentResolver,
  private val userAgentRepository: UserAgentRepository,
  private val toolRegistry: ToolRegistry,
  private val skillRegistry: SkillRegistry,
  private val agentHookRepository: AgentHookRepository,
  private val heartbeatDao: HeartbeatDao,
  private val agentFilesRepository: AgentFilesRepository,
  private val planRepository: PlanRepository,
  private val userSkillRepository: UserSkillRepository,
) : ViewModel() {

  private val agentIdFlow = MutableStateFlow<String?>(null)

  private val _state = MutableStateFlow(AgentDetailState())
  val state: StateFlow<AgentDetailState> = _state.asStateFlow()

  init {
    // The live part of the state — this agent's active heartbeats (as in the old inspector): the subscription
    // re-establishes itself when agentId changes, reload() neither touches nor duplicates it.
    viewModelScope.launch {
      heartbeatsFlow().collect { hb -> _state.update { it.copy(activeHeartbeats = hb) } }
    }
    // The agent's plans (Task 12) — the same live subscription as the heartbeats: PlanRepository.observeByAgent
    // re-establishes itself when agentId changes, reload() neither touches nor duplicates it.
    viewModelScope.launch {
      plansFlow().collect { rows -> _state.update { it.copy(plans = rows) } }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun heartbeatsFlow(): Flow<List<HeartbeatEntity>> = agentIdFlow
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else heartbeatDao.observeActiveByAgent(id) }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun plansFlow(): Flow<List<AgentDetailPlanRow>> = agentIdFlow
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else planRepository.observeByAgent(id) }
    .map { snapshots -> snapshots.map { it.toPlanRow() }.sortedByDescending { row -> row.updatedAt } }

  /** Opens the screen for [agentId]; called once from `LaunchedEffect(agentId)` on the route. */
  fun select(agentId: String) {
    if (agentIdFlow.value == agentId) return
    agentIdFlow.value = agentId
    reload()
  }

  /**
   * Rebuilds everything except [AgentDetailState.activeHeartbeats] (that part is already live) — the
   * definition, tools/skills/hooks/files. Called from [select] and again after every mutating action
   * ([duplicate] does not mutate THIS agent and does not call reload), so the screen reflects its own
   * edit right away.
   */
  private fun reload() {
    val id = agentIdFlow.value ?: return
    _state.update { it.copy(isLoading = true, actionError = null) }
    viewModelScope.launch {
      try {
        val definition = agentResolver.resolve(id)
        // Sorted by what is visible on screen (the name), not by the raw id.
        val toolRows = definition?.toolNames.orEmpty()
          .map(::resolveToolRow)
          .sortedBy { CapabilityNames.tool(it.name) }
        // Names of the agent's own skills: a custom skill's manifest has only the id, the name lives in Room.
        val ownSkillNames = if (definition != null) {
          userSkillRepository.getForAgent(id).associate { it.id to it.name }
        } else {
          emptyMap()
        }
        val skillRows = definition?.skillIds.orEmpty()
          .map { skillId -> resolveSkillRow(skillId, ownSkillNames[skillId]) }
          .sortedBy { it.title }
        // Hooks/files only for an agent that really resolves: skip them for a nonexistent id rather than going
        // to agentFilesRepository with a potentially "dirty" id (see sandboxRoot).
        val hooks = if (definition != null) agentHookRepository.getForAgent(id) else emptyList()
        val hookRows = hooks.map { hook ->
          AgentDetailHookRow(
            hook = hook,
            recentRuns = agentHookRepository.getRunsForHook(hook.id).take(RECENT_RUNS_SHOWN),
          )
        }
        val files = if (definition != null) agentFilesRepository.list(id) else emptyList()
        val invalid = toolRows.any { !it.available } || skillRows.any { !it.available }
        _state.update {
          it.copy(
            isLoading = false,
            definition = definition,
            tools = toolRows,
            skills = skillRows,
            hooks = hookRows,
            files = files,
            hasInvalidCapabilities = invalid,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, actionError = tr("Не удалось загрузить агента.", "Couldn't load the agent.")) }
      }
    }
  }

  /** Never throws: an unregistered tool name (APK downgrade) becomes an honest "unavailable" row instead of an IndexOutOfBounds/NPE further down the screen. */
  private fun resolveToolRow(name: String): AgentDetailToolRow {
    val tool = toolRegistry.get(name)
    return if (tool != null) {
      AgentDetailToolRow(name = name, description = tool.description, riskClass = tool.riskClass, available = true)
    } else {
      AgentDetailToolRow(
        name = name,
        description = tr("Инструмент недоступен в этой версии приложения", "This tool isn't available in this version of the app"),
        riskClass = null,
        available = false,
      )
    }
  }

  /**
   * Mirrors [resolveToolRow] for an [AgentDefinition.skillIds] entry the composite [SkillRegistry] no longer resolves.
   * [ownName] — the name of the agent's own skill from Room (empty/absent — the built-in skill's name is used).
   */
  private suspend fun resolveSkillRow(id: String, ownName: String?): AgentDetailSkillRow {
    val skill = skillRegistry.get(id)
    val title = ownName?.takeIf { it.isNotBlank() } ?: CapabilityNames.skill(id)
    return if (skill != null) {
      AgentDetailSkillRow(id = id, title = title, description = skill.manifest.description, whenToUse = skill.manifest.whenToUse, available = true)
    } else {
      AgentDetailSkillRow(
        id = id,
        title = title,
        description = tr("Навык недоступен в этой версии приложения", "This skill isn't available in this version of the app"),
        whenToUse = "",
        available = false,
      )
    }
  }

  // --- Actions (by source — see AgentDetailState.canEdit/canToggleEnabled/canArchive) ----------------

  /**
   * Forks the current agent (a preset OR the user's own USER agent) into a new independent USER draft —
   * the same operation as [com.aiopenpocket.ui.agents.builder.AgentBuilderViewModel.cloneFrom], called
   * here directly instead of going through the builder itself (this screen already holds
   * [userAgentRepository]). [onReady] gets the fresh clone's id — the route navigates to
   * `agent_builder/{id}`, which opens an ALREADY filled-in (not empty) draft, exactly the
   * "prefilled-clone" from the Task 21 brief.
   */
  fun duplicate(onReady: (String) -> Unit) {
    val definition = _state.value.definition ?: return
    viewModelScope.launch {
      try {
        val cloned = userAgentRepository.clonePreset(definition)
        // The copy's skills are passed explicitly: saveDraft replaces the agent's skills ENTIRELY with the
        // given list, and a call without skills erased the ones clonePreset had just copied — a copy of
        // one's own agent with a skill immediately got stuck on unknown_skill (just as is already done in
        // AgentBuilderViewModel.cloneFrom).
        // The copy's name/description are in the interface language (a preset has English ones); as in
        // AgentBuilderViewModel.cloneFrom, in Russian the result is the same as before.
        val renamed = userAgentRepository.saveDraft(
          definition = cloned.copy(
            name = tr("Копия: ${definition.displayName}", "Copy: ${definition.displayName}"),
            description = definition.displayDescription,
          ),
          skills = userSkillRepository.getForAgent(cloned.id),
          hooks = cloned.hooks,
        )
        onReady(renamed.id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(actionError = tr("Не удалось создать копию агента.", "Couldn't duplicate the agent.")) }
      }
    }
  }

  /**
   * "Disable/Enable": PUBLISHED → DRAFT (it disappears from the roster
   * [com.aiopenpocket.agent.core.agent.AgentResolver.observeRoster], but stays resolvable and can be
   * republished from here) or DRAFT/ARCHIVED → PUBLISHED. Republishing passes the agent's CURRENT hooks
   * and own skills: [UserAgentRepository.publish] unconditionally overwrites both with the given set, and
   * the validator considers a skill "own" only if it is in `skills`. Without the skills "Enable" on an
   * agent with its own skill failed on `unknown_skill` (and silently: the screen did not show the error),
   * while a successful enable would have erased the skills themselves.
   */
  fun toggleEnabled() {
    val definition = _state.value.definition ?: return
    if (definition.source != AgentSource.USER) return
    viewModelScope.launch {
      try {
        if (definition.status == AgentStatus.PUBLISHED) {
          // unpublish, not saveDraft: unpublishing is now a separate explicit action — saveDraft no longer
          // resets the status (otherwise the wizard's autosave unpublished the agent on every edit). A
          // disabled agent is visible in the "Drafts" section.
          userAgentRepository.unpublish(definition.id)
        } else {
          userAgentRepository.publish(
            id = definition.id,
            skills = userSkillRepository.getForAgent(definition.id),
            hooks = _state.value.hooks.map { it.hook },
          )
        }
        reload()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Not e.message: that is the repository's English `require` with validator codes.
        val message = if (definition.status == AgentStatus.PUBLISHED) {
          tr("Не удалось отключить агента.", "Couldn't disable the agent.")
        } else {
          tr(
            "Не удалось включить агента — открой «Изменить» и проверь шаги.",
            "Couldn't enable the agent — open “Edit” and check the steps.",
          )
        }
        _state.update { it.copy(isLoading = false, actionError = message) }
      }
    }
  }

  /** Archives the current (necessarily USER) agent — the row is kept, only the status changes. */
  fun archive() {
    val definition = _state.value.definition ?: return
    if (definition.source != AgentSource.USER) return
    viewModelScope.launch {
      try {
        userAgentRepository.archive(definition.id)
        reload()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, actionError = tr("Не удалось архивировать агента.", "Couldn't archive the agent.")) }
      }
    }
  }

  companion object {
    /** Cap on how many recent `agent_hook_runs` rows the Hooks section shows per hook (the repository itself keeps up to 100 — see [AgentHookRepository.KEEP_RUNS]). */
    private const val RECENT_RUNS_SHOWN = 5
  }
}

/** Mirrors `com.aiopenpocket.ui.plan.PlanListViewModel`'s own `PlanSnapshot.toRow()` — kept local (not shared) since the two rows carry slightly different fields and neither depends on the other's package. */
private fun PlanSnapshot.toPlanRow(): AgentDetailPlanRow {
  val doneStatuses = setOf(StepStatus.COMPLETED, StepStatus.SKIPPED)
  val statusByStepId = stepStates.associateBy { it.stepId }
  return AgentDetailPlanRow(
    planId = plan.id,
    objective = version.objective,
    status = plan.status,
    stepsCompleted = version.steps.count { statusByStepId[it.id]?.status in doneStatuses },
    stepsTotal = version.steps.size,
    updatedAt = plan.updatedAt,
  )
}
