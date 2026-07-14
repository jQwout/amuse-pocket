package com.aiopenpocket.agent.core.plan.schedule

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatTargetType
import com.aiopenpocket.agent.core.plan.execution.PlanExecutor
import com.aiopenpocket.agent.core.plan.execution.PlanScheduleSync
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A typed reference to one [PlanStep] of one approved `AgentPlan` version (Task 11 Step 1 of the
 * Structured Plan Engine; design spec `2026-07-12-structured-plan-engine-design.md` section 12.1) —
 * exactly what a [HeartbeatTargetType.PLAN_STEP] row's [HeartbeatEntity.planId]/
 * [HeartbeatEntity.planVersion]/[HeartbeatEntity.planStepId] triple encodes. A free-text
 * [HeartbeatEntity.prompt] is never treated as this — see [HeartbeatEntity.planTarget] for the read side.
 */
data class PlanHeartbeatTarget(
  val planId: String,
  val planVersion: Int,
  val stepId: String,
)

/**
 * The typed [PlanHeartbeatTarget] this heartbeat resumes, or null for an ordinary
 * [HeartbeatTargetType.GENERIC_AGENT] row (or defensively, a [HeartbeatTargetType.PLAN_STEP] row
 * somehow missing one of the three fields — [PlanScheduler] never persists one without all three).
 */
val HeartbeatEntity.planTarget: PlanHeartbeatTarget?
  get() {
    if (targetType != HeartbeatTargetType.PLAN_STEP.name) return null
    val plan = planId ?: return null
    val version = planVersion ?: return null
    val step = planStepId ?: return null
    return PlanHeartbeatTarget(plan, version, step)
  }

/** [PlanScheduler.selectNextDue]'s pure result — the step [com.aiopenpocket.agent.workers.HeartbeatRunner]
 * should resume next, and the absolute epoch millis it becomes (or already is) due. */
internal data class NextDueStep(val step: PlanStep, val dueAt: Long)

/**
 * The real [PlanScheduleSync] implementation (Task 11 of the Structured Plan Engine; plan's Task 11
 * Step 3) — supersedes Task 5's no-op `PlanScheduleSyncBindings` binding that used to live in
 * [com.aiopenpocket.agent.core.plan.execution.PlanCommands]; that class's own `approve`/`pause`/
 * `resume`/`cancel` call sites do not change (they already called through the [PlanScheduleSync] port).
 *
 * [sync] figures out the SINGLE next-due/runnable step of an ACTIVE plan — whether or not that step
 * itself carries a `PlanStep.schedule` — and arms exactly one [HeartbeatTargetType.PLAN_STEP]
 * heartbeat for it, replacing whatever was armed before. [cancel] tears down every heartbeat
 * currently targeting the plan.
 *
 * ## Why ONE heartbeat per plan, not one per scheduled step
 *
 * A `PlanRunner.run` call with the DEFAULT `PlanRunBudget` (`maxStepsPerRun = 1`) executes exactly
 * one step per wake, so only one step is ever "next" at a time regardless of how many of the plan's
 * OTHER steps also carry a schedule. This class always (re)arms exactly one heartbeat targeting
 * whichever step [selectNextDue] picks, and [com.aiopenpocket.agent.workers.HeartbeatRunner] calls
 * [sync] again after every fire to pick the NEW next-due step (which may be a different one,
 * scheduled or not) — this is how a multi-step [com.aiopenpocket.agent.core.plan.model.PlanHorizon.PROGRAM]
 * plan advances across many separate wakes. This is a deliberate narrowing of the plan document's
 * more literal "for each approved scheduled step... one heartbeat" Step 3 sketch, flagged here and in
 * the Task 11 report: scheduling N steps' heartbeats independently would need its own per-step
 * staleness/concurrency bookkeeping that is out of this task's file-list scope (no changes to
 * `PlanStateMachine`/`PlanRunner`'s own step-selection are made here).
 *
 * ## Staleness
 *
 * [sync] always re-reads [PlanRepository.snapshot] fresh — never trusts a caller-held one. A
 * previously-armed row whose `(planStepId, planVersion)` no longer matches the freshly computed
 * target (a material revision landed, or that step finished/became unrunnable/blocked) is cancelled
 * before a new one is armed — design spec section 12.1's "Cancel stale version rows".
 *
 * ## What this class deliberately does NOT do
 *
 * It never runs a step itself (that is [com.aiopenpocket.agent.core.plan.execution.PlanRunner]/
 * [com.aiopenpocket.agent.core.plan.execution.PlanExecutor]'s job, invoked later when the armed
 * heartbeat actually fires) and never re-implements WorkManager mechanics — [HeartbeatScheduler] is
 * the only thing this class asks to actually arm/cancel a job. It also never validates an agent's
 * enabled/published state — [com.aiopenpocket.agent.workers.HeartbeatRunner] does that at FIRE time
 * (mirroring Task 22's existing generic-agent guard), since an agent could be archived at any point
 * between this class arming a heartbeat and it actually firing.
 */
@SingleIn(AppScope::class)
class PlanScheduler @Inject constructor(
  private val planRepository: PlanRepository,
  private val heartbeatDao: HeartbeatDao,
  private val heartbeatScheduler: HeartbeatScheduler,
) : PlanScheduleSync {
  private var clock: () -> Long = System::currentTimeMillis

  /** Test seam — mirrors every other Structured Plan Engine class's own secondary constructor for a fixed time source. */
  constructor(
    planRepository: PlanRepository,
    heartbeatDao: HeartbeatDao,
    heartbeatScheduler: HeartbeatScheduler,
    clock: () -> Long,
  ) : this(planRepository, heartbeatDao, heartbeatScheduler) {
    this.clock = clock
  }

  /**
   * (Re)arms a single [HeartbeatTargetType.PLAN_STEP] heartbeat for [planId]'s next-due/runnable
   * step, or tears down any armed heartbeat if the plan is not [PlanStatus.ACTIVE], has no approved
   * version, or has no selectable step right now (design spec section 12.1: "Never schedule DRAFT,
   * AWAITING_APPROVAL, PAUSED, CANCELLED or COMPLETED plans" — [PlanStatus.NEEDS_REVISION]/
   * [PlanStatus.FAILED] are refused the same way, by the same `!= ACTIVE` check). Idempotent: calling
   * this again with nothing changed reuses the same [HeartbeatEntity.id] (so [HeartbeatScheduler.schedule]'s
   * `ExistingWorkPolicy.REPLACE`/`ExistingPeriodicWorkPolicy.UPDATE` simply re-arms the same timer)
   * rather than accumulating duplicate rows.
   */
  override suspend fun sync(planId: String) {
    val snapshot = planRepository.snapshot(planId)
    val approvedVersion = snapshot?.plan?.approvedVersion
    val existingRows = heartbeatDao.listForPlan(planId)

    if (snapshot == null || snapshot.plan.status != PlanStatus.ACTIVE || approvedVersion == null) {
      existingRows.forEach { cancelRow(it) }
      return
    }

    val target = selectNextDue(snapshot, clock())
    if (target == null) {
      existingRows.forEach { cancelRow(it) }
      return
    }
    ensureStepState(snapshot, target)

    val reusable = existingRows.firstOrNull { it.planStepId == target.step.id && it.planVersion == approvedVersion }
    existingRows.filterNot { it.id == reusable?.id }.forEach { cancelRow(it) }

    val entity = HeartbeatEntity(
      id = reusable?.id ?: UUID.randomUUID().toString(),
      title = titleFor(snapshot, target.step),
      mode = "agent",
      conversationId = snapshot.plan.conversationId,
      kind = if (target.step.schedule?.kind == ScheduleKind.PERIODIC) "periodic" else "one_time",
      fireAt = target.dueAt,
      intervalMinutes = target.step.schedule?.intervalMinutes,
      status = "scheduled",
      agentId = snapshot.plan.agentId,
      targetType = HeartbeatTargetType.PLAN_STEP.name,
      planId = planId,
      planVersion = approvedVersion,
      planStepId = target.step.id,
    )
    heartbeatDao.insert(entity)
    heartbeatScheduler.schedule(entity)
  }

  /** Tears down every heartbeat currently targeting [planId] — called on pause/cancel, and whenever
   * [sync] itself finds nothing left to schedule. */
  override suspend fun cancel(planId: String) {
    heartbeatDao.listForPlan(planId).forEach { cancelRow(it) }
  }

  private suspend fun cancelRow(entity: HeartbeatEntity) {
    heartbeatScheduler.cancel(entity.id)
    heartbeatDao.setStatus(entity.id, "cancelled")
  }

  /**
   * Persists a first [PlanStepState] row (status [StepStatus.PENDING], `nextDueAt` = [target]'s
   * freshly computed due time) the FIRST time a step is targeted, so a LATER [sync] call (e.g. after
   * a pause/resume cycle, or [com.aiopenpocket.agent.workers.HeartbeatRunner] re-arming the next step
   * after this one fires) reads the SAME due time back via [dueAtFor]'s `state?.nextDueAt` branch
   * instead of restarting a delay/interval countdown from whatever "now" that later call happens to
   * run at. No-op once a [PlanStepState] row already exists for this step — this never overwrites
   * real progress (attempts/status) [PlanExecutor] itself owns.
   */
  private suspend fun ensureStepState(snapshot: PlanSnapshot, target: NextDueStep) {
    if (snapshot.stepStates.any { it.stepId == target.step.id }) return
    planRepository.updateStepState(
      PlanStepState(
        planId = snapshot.plan.id,
        version = snapshot.version.version,
        stepId = target.step.id,
        status = StepStatus.PENDING,
        attempts = 0,
        nextDueAt = target.dueAt,
        startedAt = null,
        completedAt = null,
      ),
    )
  }

  private fun titleFor(snapshot: PlanSnapshot, step: PlanStep): String =
    "${snapshot.version.objective}: ${step.title}".take(120)

  companion object {
    /** Mirrors `PlanRunner`'s own private `SELECTABLE_STATUSES` exactly (a step in one of these — or
     * with no [PlanStepState] row at all yet — is a candidate for "what runs next"). Duplicated
     * rather than shared, matching how `PlanRunner` itself already keeps its own private copy of
     * `PlanExecutor`'s "step is done" status set instead of exposing a shared constants file. */
    private val SELECTABLE_STATUSES = setOf(StepStatus.PENDING, StepStatus.READY, StepStatus.FAILED)

    /**
     * Pure "what runs next, and when" decision (this task's own instruction: "Keep the pure...
     * decision unit-testable") — topological (via [PlanExecutor.isRunnable], reused verbatim from
     * `PlanRunner`) + due-date, in declared step order; ties broken by first-in-order, same convention
     * `PlanRunner.selectStep` uses. Unlike that method, this one is never given a `pinnedStepId`
     * (nothing "resumes" one specific step here — this is what CREATES the pin a later
     * [com.aiopenpocket.agent.workers.HeartbeatRunner] fire uses) and it reports WHEN a not-yet-due
     * candidate becomes due instead of only a yes/no. Null when no step is currently selectable at all
     * (every step COMPLETED/SKIPPED, or the only remaining ones are BLOCKED/EXECUTING/VERIFYING/
     * unrunnable) — the whole-plan-done/blocked cases [sync] treats identically (cancel and stop).
     */
    internal fun selectNextDue(snapshot: PlanSnapshot, now: Long): NextDueStep? {
      val statusByStepId = snapshot.stepStates.associateBy { it.stepId }
      return snapshot.version.steps
        .asSequence()
        .filter { step ->
          val state = statusByStepId[step.id]
          val selectable = state == null || state.status in SELECTABLE_STATUSES
          selectable && PlanExecutor.isRunnable(step, snapshot.stepStates)
        }
        .map { step -> step to dueAtFor(step, statusByStepId[step.id], now) }
        .minByOrNull { (_, dueAt) -> dueAt }
        ?.let { (step, dueAt) -> NextDueStep(step, dueAt) }
    }

    /**
     * A step with no `PlanStep.schedule` is always due right now — mirrors `PlanRunner.isDue`. A
     * scheduled step that already carries a [PlanStepState.nextDueAt] uses THAT (durable across
     * repeated [selectNextDue]/[sync] calls — see [ensureStepState]); one with no progress row yet
     * computes a fresh due time from `PlanSchedule.delayMinutes` (falling back to `intervalMinutes`
     * for a periodic step with no explicit initial delay — the same fallback `HeartbeatTool.schedule`
     * has always used for its own periodic case: "первый запуск через delay, либо через полный интервал").
     */
    private fun dueAtFor(step: PlanStep, state: PlanStepState?, now: Long): Long {
      val schedule = step.schedule ?: return now
      state?.nextDueAt?.let { return it }
      val minutes = schedule.delayMinutes ?: schedule.intervalMinutes ?: 0L
      return now + TimeUnit.MINUTES.toMillis(minutes)
    }
  }
}

/** Task 11's binding for [PlanScheduleSync] — supersedes Task 5's no-op (that binding container is
 * removed from [com.aiopenpocket.agent.core.plan.execution.PlanCommands]'s own file; see that
 * interface's kdoc there). */
@ContributesTo(AppScope::class)
@BindingContainer
object PlanSchedulerBindings {

  @Provides
  @SingleIn(AppScope::class)
  fun providePlanScheduleSync(impl: PlanScheduler): PlanScheduleSync = impl
}
