package com.aiopenpocket.ui.plan

// @trace structured-plan-engine-907066

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.plan.execution.PlanCommands
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in the plan detail's step timeline — a step's declared content plus its live runtime progress. */
data class PlanStepRow(
  val id: String,
  val title: String,
  val instruction: String,
  val dependsOn: Set<String>,
  val status: StepStatus,
  val attempts: Int,
  val completionRuleType: CompletionRuleType,
  /** Only meaningful for a [PlanHorizon.PROGRAM] step with a schedule — see `PlanCard.kt`'s own `nextDueAtFor`. */
  val nextDueAt: Long?,
  /** Most recent [com.aiopenpocket.agent.core.plan.model.PlanEvidence.summary] whose `stepId` is THIS step's [id], if any. */
  val lastEvidenceSummary: String?,
)

/** One row for the plan-level "Критерии выполнения" section — [satisfied] is true iff some persisted evidence references this criterion (design spec: a required criterion needs real evidence, never a bare model claim). */
data class PlanCriterionRow(
  val id: String,
  val description: String,
  val required: Boolean,
  val satisfied: Boolean,
)

data class PlanDetailState(
  val isLoading: Boolean = true,
  val planId: String? = null,
  val agentId: String? = null,
  val conversationId: String? = null,
  val objective: String? = null,
  val assumptions: List<String> = emptyList(),
  val constraints: List<String> = emptyList(),
  val horizon: PlanHorizon? = null,
  val status: PlanStatus? = null,
  val version: Int? = null,
  val steps: List<PlanStepRow> = emptyList(),
  val criteria: List<PlanCriterionRow> = emptyList(),
  /** Newest first — see [PlanDetailViewModel.eventsFlow]. */
  val events: List<PlanEvent> = emptyList(),
  /** True once the live snapshot resolved to nothing for this id (plan deleted, or a bad navigation arg). */
  val notFound: Boolean = false,
  /** Message from the most recently FAILED pause/resume/cancel action, or null. */
  val actionError: String? = null,
)

private val TERMINAL_PLAN_STATUSES = setOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)

val PlanDetailState.canPause: Boolean get() = status == PlanStatus.ACTIVE
val PlanDetailState.canResume: Boolean get() = status == PlanStatus.PAUSED
val PlanDetailState.canCancel: Boolean get() = status != null && status !in TERMINAL_PLAN_STATUSES

/**
 * Backs the `plan/{planId}` route (Task 12, design spec section 15: "отдельная detail view с
 * timeline событий"). Combines two independent live subscriptions — [PlanRepository.observe] (the
 * plan/version/steps/evidence snapshot) and [PlanRepository.observeEvents] (the append-only audit
 * log) — into one [PlanDetailState], and issues pause/resume/cancel through [PlanCommands] (Task 5),
 * the same typed surface `ChatViewModel` uses; this screen invents no second lifecycle mechanism.
 *
 * Deliberately does NOT expose approve/request-changes here — those stay exclusively a chat/
 * `PlanCard` action (see that file's kdoc "Do NOT invent a second approval mechanism"), so a plan
 * awaiting approval is still only approvable from the one place the user was actually shown the
 * proposal.
 *
 * planId arrives AFTER construction via [select] — same reasoning as
 * `com.aiopenpocket.ui.agents.detail.AgentDetailViewModel`/[PlanListViewModel].
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class PlanDetailViewModel(
  private val planRepository: PlanRepository,
  private val planCommands: PlanCommands,
) : ViewModel() {

  private val planIdFlow = MutableStateFlow<String?>(null)

  private val _state = MutableStateFlow(PlanDetailState())
  val state: StateFlow<PlanDetailState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      snapshotFlow().collect { snapshot -> _state.update { it.applySnapshot(snapshot) } }
    }
    viewModelScope.launch {
      eventsFlow().collect { events -> _state.update { it.copy(events = events) } }
    }
  }

  /**
   * `filterNotNull()` so this collector (and [applySnapshot]'s `isLoading`/`notFound` derivation)
   * never runs off [planIdFlow]'s own initial `null` value — only ever off a REAL id from [select].
   * Otherwise `init`'s collector would fire the instant it starts (before any navigation arg
   * arrives), flip `isLoading` false with no objective loaded, and flash "План не найден" for one
   * frame ahead of the real load.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun snapshotFlow(): Flow<PlanSnapshot?> = planIdFlow.filterNotNull().flatMapLatest { id ->
    planRepository.observe(id)
  }

  /** Newest first — `PlanRepository.observeEvents` itself documents oldest-first, but a detail-screen timeline reads better as "what just happened" on top. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun eventsFlow(): Flow<List<PlanEvent>> = planIdFlow.filterNotNull().flatMapLatest { id ->
    planRepository.observeEvents(id)
  }.map { events -> events.sortedByDescending { it.createdAt } }

  /** Opens the screen for [planId]; called once from `LaunchedEffect(planId)` on the route. */
  fun select(planId: String) {
    if (planIdFlow.value == planId) return
    planIdFlow.value = planId
    _state.update { it.copy(isLoading = true, planId = planId, actionError = null) }
  }

  fun pause() = withPlanId { id -> runCommand { planCommands.pause(id) } }
  fun resume() = withPlanId { id -> runCommand { planCommands.resume(id) } }
  fun cancel() = withPlanId { id -> runCommand { planCommands.cancel(id) } }

  private inline fun withPlanId(block: (String) -> Unit) {
    val id = _state.value.planId ?: return
    block(id)
  }

  /** Mirrors `ChatViewModel.runPlanCommand`: clears any stale [PlanDetailState.actionError] up front so a retried action doesn't leave a previous failure's message stuck on screen after it succeeds. */
  private fun runCommand(action: suspend () -> Unit) {
    viewModelScope.launch {
      _state.update { it.copy(actionError = null) }
      try {
        action()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(actionError = e.message ?: "Не удалось выполнить действие с планом") }
      }
    }
  }
}

/** Projects a live [PlanSnapshot] (or its absence) onto [PlanDetailState], preserving [PlanDetailState.events]/[PlanDetailState.actionError] (owned by the OTHER subscription/by action calls respectively — see [PlanDetailViewModel.init]). */
private fun PlanDetailState.applySnapshot(snapshot: PlanSnapshot?): PlanDetailState {
  if (snapshot == null) return copy(isLoading = false, notFound = planId != null)

  val stepStateByStepId = snapshot.stepStates.associateBy { it.stepId }
  val evidenceByStepId = snapshot.evidence.filter { it.stepId != null }.groupBy { it.stepId }
  val steps = snapshot.version.steps.map { step ->
    val stepState = stepStateByStepId[step.id]
    val lastEvidence = evidenceByStepId[step.id]?.maxByOrNull { it.createdAt }
    PlanStepRow(
      id = step.id,
      title = step.title,
      instruction = step.instruction,
      dependsOn = step.dependsOn,
      status = stepState?.status ?: StepStatus.PENDING,
      attempts = stepState?.attempts ?: 0,
      completionRuleType = step.completionRule.type,
      nextDueAt = stepState?.nextDueAt,
      lastEvidenceSummary = lastEvidence?.summary,
    )
  }
  val criteria = snapshot.version.criteria.map { criterion ->
    PlanCriterionRow(
      id = criterion.id,
      description = criterion.description,
      required = criterion.required,
      satisfied = snapshot.evidence.any { it.criterionId == criterion.id },
    )
  }
  return copy(
    isLoading = false,
    planId = snapshot.plan.id,
    agentId = snapshot.plan.agentId,
    conversationId = snapshot.plan.conversationId,
    objective = snapshot.version.objective,
    assumptions = snapshot.version.assumptions,
    constraints = snapshot.version.constraints,
    horizon = snapshot.plan.horizon,
    status = snapshot.plan.status,
    version = snapshot.version.version,
    steps = steps,
    criteria = criteria,
    notFound = false,
  )
}
