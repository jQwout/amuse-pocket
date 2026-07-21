package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in [PlanListState] — a flattened, display-ready projection of one [PlanSnapshot]. */
data class PlanListRow(
  val planId: String,
  val objective: String,
  val status: PlanStatus,
  val stepsCompleted: Int,
  val stepsTotal: Int,
  val updatedAt: Long,
)

private val TERMINAL_PLAN_STATUSES = setOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)
private val TERMINAL_STEP_STATUSES = setOf(StepStatus.COMPLETED, StepStatus.SKIPPED)

private fun PlanSnapshot.toRow(): PlanListRow {
  val statusByStepId = stepStates.associateBy { it.stepId }
  return PlanListRow(
    planId = plan.id,
    objective = version.objective,
    status = plan.status,
    stepsCompleted = version.steps.count { statusByStepId[it.id]?.status in TERMINAL_STEP_STATUSES },
    stepsTotal = version.steps.size,
    updatedAt = plan.updatedAt,
  )
}

/**
 * State for the full plans list of one agent (Task 12, design spec section 15: "На экране агента:
 * список active/completed/paused plans"). [active] additionally holds every PRE-execution status
 * (DRAFT/NEEDS_INFO/AWAITING_APPROVAL/NEEDS_REVISION) alongside [PlanStatus.ACTIVE] itself — a plan
 * that exists but hasn't reached a terminal or paused state yet is still "in flight", and folding it
 * into [active] means no plan for this agent is ever silently missing from all three sections. Each
 * row still carries its own precise [PlanListRow.status] so the UI can label/badge it accurately
 * even inside a shared section.
 */
data class PlanListState(
  val isLoading: Boolean = true,
  val agentId: String? = null,
  val active: List<PlanListRow> = emptyList(),
  val paused: List<PlanListRow> = emptyList(),
  val completed: List<PlanListRow> = emptyList(),
)

/**
 * Backs the `plans/{agentId}` route (Task 12) — a live, three-section roster of every plan ever
 * proposed for one agent, sourced entirely from [PlanRepository.observeByAgent]. Purely a read model:
 * unlike [PlanDetailViewModel], this screen has no per-plan actions of its own — tapping a row
 * navigates to `plan/{planId}` instead.
 *
 * agentId arrives AFTER construction via [select], mirroring
 * `com.aiopenpocket.ui.agents.detail.AgentDetailViewModel`'s own reasoning: a `metroViewModel()`-obtained
 * instance cannot take runtime navigation arguments through its constructor.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class PlanListViewModel(
  private val planRepository: PlanRepository,
) : ViewModel() {

  private val agentIdFlow = MutableStateFlow<String?>(null)

  private val _state = MutableStateFlow(PlanListState())
  val state: StateFlow<PlanListState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      plansFlow().collect { rows ->
        _state.update {
          it.copy(
            isLoading = false,
            active = rows.filter { row -> row.status !in TERMINAL_PLAN_STATUSES && row.status != PlanStatus.PAUSED }
              .sortedByDescending { row -> row.updatedAt },
            paused = rows.filter { row -> row.status == PlanStatus.PAUSED }
              .sortedByDescending { row -> row.updatedAt },
            completed = rows.filter { row -> row.status in TERMINAL_PLAN_STATUSES }
              .sortedByDescending { row -> row.updatedAt },
          )
        }
      }
    }
  }

  /**
   * `filterNotNull()` (not `flatMapLatest { if (id == null) flowOf(emptyList()) ... }`) so this
   * collector does nothing at all — never touching [PlanListState.isLoading]/the section lists —
   * until [select] actually runs. Without it, [init]'s collector would react to [agentIdFlow]'s own
   * initial `null` value the instant it starts (before any navigation arg arrives) and flip
   * `isLoading` false with three empty sections, flashing the "нет планов" empty state for one frame
   * ahead of the real load.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun plansFlow() = agentIdFlow.filterNotNull().flatMapLatest { id ->
    planRepository.observeByAgent(id)
  }.map { snapshots -> snapshots.map { it.toRow() } }

  /** Opens the screen for [agentId]; called once from `LaunchedEffect(agentId)` on the route. */
  fun select(agentId: String) {
    if (agentIdFlow.value == agentId) return
    agentIdFlow.value = agentId
    _state.update { it.copy(isLoading = true, agentId = agentId) }
  }
}
