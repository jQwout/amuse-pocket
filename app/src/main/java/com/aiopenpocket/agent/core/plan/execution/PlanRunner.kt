package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One bounded request to advance a GOAL run (plan's Task 10 Step 1 sketch, reproduced here).
 * [planId] is nullable so a chat-driven caller ([com.aiopenpocket.agent.loop.StructuredPlanOrchestrator])
 * that only knows [conversationId] can ask [PlanRunner] to resolve "the" plan itself (see
 * [PlanRunner]'s own kdoc "Resolving planId when null"); [requestedStepId] pins selection to one
 * specific step (Task 11's typed heartbeat target — design spec section 12.1 — is expected to always
 * supply both [planId] and this). [background] is threaded straight to [PlanExecutor.execute]'s own
 * `interactiveApproval = !background`.
 */
data class PlanRunRequest(
  val conversationId: String,
  val planId: String?,
  val requestedStepId: String? = null,
  val background: Boolean = false,
  val budget: PlanRunBudget = PlanRunBudget(),
  /** LIVE runs tools for real; PREVIEW (builder test-run) simulates side-effecting tools. Threaded to [PlanExecutor.execute]. */
  val executionMode: ExecutionMode = ExecutionMode.LIVE,
)

/** Terminal outcome of one [PlanRunner.run] call (plan's Task 10 Step 1 sketch, reproduced here). */
data class PlanRunResult(
  val planId: String,
  val outcome: PlanRunOutcome,
  val executedStepIds: List<String>,
  val blockedReason: String?,
)

/**
 * Advances an approved plan by zero or more runnable/due steps within one bounded budget (design
 * spec `2026-07-12-structured-plan-engine-design.md` sections 9/10/12, plan Task 10) — the
 * "select next runnable/due step, execute it, repeat until stopped" loop [PlanExecutor]'s own kdoc
 * explicitly assigns here: *"Selecting the next runnable/due step across a whole GOAL run
 * (topological order, schedule due dates, budget decrement across steps, concurrency lease) is
 * `PlanRunner`'s job (Task 10)."*
 *
 * ## Division of labor with [PlanExecutor]
 *
 * [PlanRunner] never talks to an LLM, a tool, or a hook directly — it only decides WHICH step runs
 * next and when to stop, then hands the actual work to [PlanExecutor.execute] (one step per call;
 * verification/evidence/hooks all happen inside that one call — see its own kdoc). [PlanRunner] is
 * the ONLY thing that ever probes [PlanRepository.tryComplete] (whole-plan completion) —
 * `PlanExecutor.recordOutcome`'s own kdoc explicitly leaves that write to "PlanRunner, Task 10".
 *
 * ## Why a run can execute FEWER steps than exist
 *
 * [run] stops the moment any of these becomes true, reporting the [PlanRunResult] the loop had built
 * up to that point:
 * - the plan's FRESH [PlanStatus] is not [PlanStatus.ACTIVE] (paused, awaiting approval/revision,
 *   still drafting, or already terminal) — re-checked before EVERY step selection, since a hook/
 *   another run/the user could have moved it since the previous iteration (design spec section 16:
 *   "Version conflict: run заканчивается на старом snapshot");
 * - no step is currently runnable/due (see [selectStep]) — either every step is COMPLETED/SKIPPED
 *   (in which case [PlanRepository.tryComplete] is probed immediately), a step is
 *   [StepStatus.BLOCKED] awaiting manual resolution, or the remaining steps simply are not due yet;
 * - a just-executed step resolved to [StepStatus.BLOCKED] or [StepStatus.FAILED] — design spec
 *   section 16: a blocked/failed step needs a human/manual decision, not an immediate silent switch
 *   to a different step;
 * - [PlanRunBudget.maxStepsPerRun] steps have already run this call.
 *
 * A step resolving to [StepStatus.READY] (`PlanVerifier`'s `Retryable` outcome — design spec section
 * 4: "VERIFYING ├─ retryable → ACTIVE") is NOT a stop condition: the loop simply selects it again
 * next iteration, still bounded by both [PlanRunBudget.maxRetriesPerStep] (inside [PlanExecutor]) and
 * [PlanRunBudget.maxStepsPerRun] (here), so this can never spin unboundedly.
 *
 * ## Concurrency
 *
 * A per-plan-id entry in [inFlight], held for the whole [run] call, so a foreground chat GOAL turn and a
 * background heartbeat (Task 11) can never execute the SAME plan at once (plan's Task 10 Step 3:
 * "per-conversation lock or repository lease so foreground and heartbeat cannot execute the same
 * plan concurrently"). The claim is non-blocking — never a suspending wait — so a second concurrent
 * caller is refused immediately with a [PlanRunOutcome.PARTIAL] "already running" result rather than
 * silently queuing behind the first, which could otherwise double-run the exact step the second
 * caller expected to observe.
 *
 * ## Crash recovery (Task 14)
 *
 * [inFlight] above only guards concurrency WITHIN one live process; it says nothing about a step left
 * [StepStatus.EXECUTING] because the PROCESS itself died mid-[PlanExecutor.execute] (no in-process holder
 * survives to release/retry it). [runLocked] calls `PlanRecovery.recoverSnapshot` once per [run] call,
 * before [selectStep] ever runs, so such a step is always repaired — to [StepStatus.COMPLETED] if
 * durable evidence already proves its side effect ran, [StepStatus.BLOCKED] otherwise — before this
 * loop can pick a next step. Without it, [SELECTABLE_STATUSES] excluding EXECUTING/VERIFYING would
 * leave a crashed plan deadlocked forever: never selectable again, never reported as blocked either.
 * See `PlanRecovery`'s own kdoc for why this never re-invokes an LLM/tool and never double-persists
 * evidence/events on a repeat call.
 *
 * ## Resolving [PlanRunRequest.planId] when null
 *
 * [run] resolves "the" plan for [PlanRunRequest.conversationId] only when [PlanRunRequest.planId] is
 * null — the most-recently-updated NON-terminal plan for that conversation, if any. Throws if none
 * exists: unlike a plan's ordinary runtime states (paused, awaiting approval, ...), "this
 * conversation has never had ANY plan proposed" is a caller precondition
 * `com.aiopenpocket.agent.loop.StructuredPlanOrchestrator.executeGoal` is expected to check itself
 * (and fall back to `com.aiopenpocket.agent.core.plan.planner.PlanPlanner` instead, design spec
 * section 9: "Если пользователь включает GOAL без approved plan, runtime сначала создаёт plan
 * proposal") — [PlanRunner] has no `PlanPlanner` dependency and cannot bootstrap a proposal itself.
 */
@SingleIn(AppScope::class)
class PlanRunner @Inject constructor(
  private val repository: PlanRepository,
  private val executor: PlanExecutor,
  private val agentResolver: AgentResolver,
) {
  private var clock: () -> Long = System::currentTimeMillis

  /** Test seam — mirrors [PlanRepository]/[PlanExecutor]'s own secondary constructor for a fixed time source. */
  constructor(
    repository: PlanRepository,
    executor: PlanExecutor,
    agentResolver: AgentResolver,
    clock: () -> Long,
  ) : this(repository, executor, agentResolver) {
    this.clock = clock
  }

  /**
   * Идентификаторы планов, выполняющихся ПРЯМО СЕЙЧАС — см. kdoc класса, раздел "Concurrency".
   *
   * Множество, а не `Map<String, Mutex>`, как было раньше. Причина: захват здесь всегда неблокирующий
   * (`tryLock`, никогда суспендящий `withLock`), то есть от мьютекса требовалась ровно одна операция —
   * атомарное «занято ли?». Ровно это и делает `add` у конкурентного множества.
   *
   * Разница не косметическая. Мьютексы заводились через `getOrPut` и НЕ УДАЛЯЛИСЬ никогда, поэтому
   * мапа росла вместе с числом планов за всю жизнь процесса, а не с числом одновременных запусков.
   * Удалить мьютекс после `unlock` было нельзя без гонки: между снятием блокировки и удалением
   * другой вызов успевал взять тот же экземпляр, после чего третий создавал НОВЫЙ — и два хода
   * исполняли один план одновременно, то есть ровно то, что эта блокировка и предотвращает.
   * Множество снимает вопрос: в нём по построению лежат только текущие запуски.
   */
  private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** Только для чтения: какие планы выполняются сейчас. */
  internal val inFlightPlanIds: Set<String> get() = inFlight

  suspend fun run(request: PlanRunRequest, provider: LlmProvider = LlmProvider.NIM): PlanRunResult {
    val planId = request.planId ?: resolvePlanId(request.conversationId)
    // add возвращает false, если id уже там — атомарно, это и есть tryLock.
    if (!inFlight.add(planId)) {
      return PlanRunResult(
        planId = planId,
        outcome = PlanRunOutcome.PARTIAL,
        executedStepIds = emptyList(),
        blockedReason = "Plan '$planId' is already executing (a foreground or background run is in flight); refusing a concurrent run.",
      )
    }
    try {
      return runLocked(planId, request, provider)
    } finally {
      // finally, а не хвост try: упавший (или отменённый) ход обязан освободить план, иначе тот
      // остался бы НАВСЕГДА невыполнимым — каждый следующий запуск получал бы «already executing»
      // от держателя, которого уже нет.
      inFlight.remove(planId)
    }
  }

  /** Resolves "the" plan for [conversationId] when the caller did not name one — see class kdoc. */
  private suspend fun resolvePlanId(conversationId: String): String {
    val candidate = repository.observeByConversation(conversationId).first()
      .filter { it.plan.status !in TERMINAL_PLAN_STATUSES }
      .maxByOrNull { it.plan.updatedAt }
    return requireNotNull(candidate) {
      "Conversation '$conversationId' has no plan to run; a proposal must exist before GOAL can execute."
    }.plan.id
  }

  private suspend fun runLocked(planId: String, request: PlanRunRequest, provider: LlmProvider): PlanRunResult {
    val executedSteps = mutableListOf<String>()
    var stepsLeft = request.budget.maxStepsPerRun
    var pinnedStepId = request.requestedStepId
    var recovered = false

    while (true) {
      var snapshot = repository.snapshot(planId)
        ?: return PlanRunResult(planId, PlanRunOutcome.FAILED, executedSteps, "Plan '$planId' does not exist.")
      check(snapshot.plan.conversationId == request.conversationId) {
        "Plan '$planId' belongs to conversation '${snapshot.plan.conversationId}', not the requested '${request.conversationId}'."
      }

      // Self-heal (Task 14): a step left EXECUTING/VERIFYING by a process death mid-step is invisible
      // to selectStep's own SELECTABLE_STATUSES below — without this, a crashed step would deadlock
      // this plan forever, never selected again and never reported as blocked. Reuses PlanRecovery's
      // repair logic directly (this class already holds the SAME PlanRepository instance) rather than
      // taking on PlanRecovery/PlanScheduleSync as a new constructor dependency of its own; only once
      // per run call — a live run's own step transitions can never themselves leave a stuck row for a
      // LATER iteration of this same loop to find. See PlanRecovery's class kdoc "Where this runs".
      if (!recovered) {
        recovered = true
        if (PlanRecovery.recoverSnapshot(repository, snapshot, clock())) {
          snapshot = repository.snapshot(planId)
            ?: return PlanRunResult(planId, PlanRunOutcome.FAILED, executedSteps, "Plan '$planId' vanished during recovery.")
        }
      }

      statusStop(snapshot)?.let { return it.copy(executedStepIds = executedSteps.toList()) }

      if (stepsLeft <= 0) {
        return PlanRunResult(planId, PlanRunOutcome.PARTIAL, executedSteps, "This run's step budget (${request.budget.maxStepsPerRun}) is used up.")
      }

      val step = selectStep(snapshot, pinnedStepId, clock())
        ?: return finishNoRunnableStep(snapshot, executedSteps, pinnedStepId)

      val agent = agentResolver.resolve(snapshot.plan.agentId)
      val stepResult = executor.execute(
        snapshot = snapshot,
        step = step,
        agent = agent,
        budget = request.budget,
        provider = provider,
        executionMode = request.executionMode,
        interactiveApproval = !request.background,
      )
      executedSteps += step.id
      stepsLeft--
      // A pinned first step (typed heartbeat target) only pins the FIRST selection; a later
      // iteration within the SAME run (e.g. a retry) falls back to ordinary topological selection.
      pinnedStepId = null

      val after = repository.snapshot(planId)
        ?: return PlanRunResult(planId, PlanRunOutcome.FAILED, executedSteps, "Plan '$planId' vanished mid-run.")
      when (after.stepStates.find { it.stepId == step.id }?.status) {
        StepStatus.COMPLETED -> {
          val completed = repository.tryComplete(planId)
          if (completed.plan.status == PlanStatus.COMPLETED) {
            repository.appendEvent(planCompletedEvent(completed))
            return PlanRunResult(planId, PlanRunOutcome.COMPLETED, executedSteps, null)
          }
          // Not every required criterion has evidence yet, or more steps remain — keep looping.
        }
        StepStatus.BLOCKED ->
          return PlanRunResult(planId, PlanRunOutcome.PARTIAL, executedSteps, stepResult.error ?: "Step '${step.id}' is blocked.")
        StepStatus.FAILED ->
          return PlanRunResult(planId, PlanRunOutcome.FAILED, executedSteps, stepResult.error ?: "Step '${step.id}' failed.")
        else -> Unit // READY (retryable within budget), or a pre-flight budget refusal — loop back and re-select.
      }
    }
  }

  /** Pre-flight status gate — re-checked fresh every iteration since a hook/other run/the user can move [PlanStatus] between iterations. Null means "ACTIVE, proceed"; [executedStepIds] is always empty here and overwritten by the caller. */
  private fun statusStop(snapshot: PlanSnapshot): PlanRunResult? = when (snapshot.plan.status) {
    PlanStatus.ACTIVE -> null
    PlanStatus.COMPLETED -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.COMPLETED, emptyList(), null)
    PlanStatus.CANCELLED -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.CANCELLED, emptyList(), "Plan is cancelled.")
    PlanStatus.FAILED -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.FAILED, emptyList(), "Plan is failed.")
    PlanStatus.PAUSED -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.PARTIAL, emptyList(), "Plan is paused; resume it before running GOAL.")
    PlanStatus.AWAITING_APPROVAL -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.PARTIAL, emptyList(), "Plan has a version awaiting approval; approve it before running GOAL.")
    PlanStatus.NEEDS_REVISION -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.PARTIAL, emptyList(), "Plan needs a new proposal before running GOAL.")
    PlanStatus.DRAFT, PlanStatus.NEEDS_INFO -> PlanRunResult(snapshot.plan.id, PlanRunOutcome.PARTIAL, emptyList(), "Plan has never been approved yet.")
  }

  private suspend fun finishNoRunnableStep(snapshot: PlanSnapshot, executedSteps: List<String>, pinnedStepId: String?): PlanRunResult {
    val planId = snapshot.plan.id
    if (pinnedStepId != null) {
      // A specific step was requested (typed heartbeat target) but isn't selectable right now.
      return PlanRunResult(planId, PlanRunOutcome.PARTIAL, executedSteps, "Requested step '$pinnedStepId' is not runnable/due right now.")
    }
    val statusByStepId = snapshot.stepStates.associateBy { it.stepId }
    val allDone = snapshot.version.steps.all { statusByStepId[it.id]?.status in PLAN_COMPLETE_STEP_STATUSES }
    if (allDone) {
      // A crash (or a pause) between the last step's COMPLETED write and runLocked's own tryComplete
      // probe leaves every step done but the plan still ACTIVE. Probe completion here too — mirrors
      // runLocked's COMPLETED branch — so a later run/heartbeat closes the plan instead of wedging it
      // ACTIVE forever.
      val completed = repository.tryComplete(planId)
      if (completed.plan.status == PlanStatus.COMPLETED) {
        repository.appendEvent(planCompletedEvent(completed))
        return PlanRunResult(planId, PlanRunOutcome.COMPLETED, executedSteps, null)
      }
      return PlanRunResult(
        planId, PlanRunOutcome.PARTIAL, executedSteps,
        "All steps are finished but the plan has not reached COMPLETED yet (check required-criteria evidence).",
      )
    }
    val blocked = snapshot.version.steps.firstOrNull { statusByStepId[it.id]?.status == StepStatus.BLOCKED }
    if (blocked != null) {
      return PlanRunResult(planId, PlanRunOutcome.PARTIAL, executedSteps, "Step '${blocked.id}' is blocked and needs manual resolution.")
    }
    return PlanRunResult(planId, PlanRunOutcome.PARTIAL, executedSteps, "No runnable or due step right now.")
  }

  private fun planCompletedEvent(snapshot: PlanSnapshot): PlanEvent = PlanEvent(
    id = UUID.randomUUID().toString(),
    planId = snapshot.plan.id,
    version = snapshot.version.version,
    stepId = null,
    type = PlanEventType.PLAN_COMPLETED,
    payload = buildJsonObject { put("totalSteps", snapshot.version.steps.size) },
    createdAt = clock(),
  )

  companion object {
    private val TERMINAL_PLAN_STATUSES = setOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)
    private val PLAN_COMPLETE_STEP_STATUSES = setOf(StepStatus.COMPLETED, StepStatus.SKIPPED)

    /** A step in one of these is selectable next — mirrors [PlanExecutor]'s own `ALREADY_HANDLED_STATUSES`, but excludes [StepStatus.BLOCKED] too (that needs manual unblocking, never an automatic re-select). */
    private val SELECTABLE_STATUSES = setOf(StepStatus.PENDING, StepStatus.READY, StepStatus.FAILED)

    /**
     * Pure step selection — topological (dependsOn, via [PlanExecutor.isRunnable]) + due-date +
     * status, in declared step order. [pinnedStepId], when non-null, restricts selection to exactly
     * that one step id (Task 11's typed heartbeat target) instead of scanning every step.
     */
    internal fun selectStep(snapshot: PlanSnapshot, pinnedStepId: String?, now: Long): PlanStep? {
      val statusByStepId = snapshot.stepStates.associateBy { it.stepId }
      val candidates = if (pinnedStepId != null) {
        snapshot.version.steps.filter { it.id == pinnedStepId }
      } else {
        snapshot.version.steps
      }
      return candidates.firstOrNull { step ->
        val state = statusByStepId[step.id]
        val selectable = state == null || state.status in SELECTABLE_STATUSES
        selectable && PlanExecutor.isRunnable(step, snapshot.stepStates) && isDue(step, state, now)
      }
    }

    /** A step with no [com.aiopenpocket.agent.core.plan.model.PlanSchedule] is always due; a scheduled one is due once its own [PlanStepState.nextDueAt] has arrived (or was never set — first run). */
    private fun isDue(step: PlanStep, state: PlanStepState?, now: Long): Boolean {
      if (step.schedule == null) return true
      val nextDueAt = state?.nextDueAt ?: return true
      return nextDueAt <= now
    }
  }
}
