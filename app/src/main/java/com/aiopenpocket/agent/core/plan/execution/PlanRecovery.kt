package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEventType
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.validation.PlanStateMachine
import com.aiopenpocket.agent.core.plan.validation.PlanStepCommand
import com.aiopenpocket.agent.core.plan.validation.StepTransitionResult
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repairs a plan's durable state after the process died mid-step (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 16, plan Task 14) — never re-executes a
 * side-effecting step just because it looks unfinished. [PlanExecutor.execute] already writes
 * [StepStatus.EXECUTING] BEFORE calling [PlanStepRunner.run] and the fully-resolved terminal status
 * AFTER (`execute`'s own "Persist step start safely" comment, Task 7's Step 5) — a process death
 * mid-run therefore always leaves the tell-tale row: [StepStatus.EXECUTING] (or, defensively,
 * [StepStatus.VERIFYING] — see [recoverStep]'s own kdoc for why that status is not actually
 * reachable under the current [PlanExecutor.recordOutcome] implementation) with NO terminal status
 * ever written. Without this class, [PlanRunner.selectStep]/`PlanScheduler.selectNextDue` both
 * exclude EXECUTING/VERIFYING from their own "selectable" status sets (by design — a step already in
 * flight must never be picked again), which means a crashed step is not just unfinished, it is
 * PERMANENTLY stuck: nothing else in the engine ever moves it out of EXECUTING again.
 *
 * ## Idempotency: evidence, not a guess, decides complete-vs-review
 *
 * [recoverStep] never re-invokes [PlanStepRunner]/an LLM/a tool — recovery has no
 * [com.aiopenpocket.agent.loop.AgentResult] to hand `PlanVerifier` (that in-memory result died with
 * the process), so it can only consult what is ALREADY durably persisted:
 * [PlanSnapshot.evidence] rows referencing [PlanStep.id] via [com.aiopenpocket.agent.core.plan.model.PlanEvidence.stepId]
 * are exactly the "idempotency evidence" design section 16 requires before ever trusting a
 * side-effecting step is done ("side-effect tool не повторяется без idempotency evidence"). Scoped
 * to evidence created AT OR AFTER [PlanStepState.startedAt] of the CURRENT (crashed) attempt — not
 * "ever, across any earlier attempt" — because [PlanVerifier.verify] can persist evidence for one
 * required criterion while STILL leaving the step [StepStatus.READY] for a retry (another required
 * criterion remains unsatisfied); counting THAT stale evidence as proof of THIS attempt's own
 * side effect would be exactly the false-positive this whole task exists to prevent.
 * - Fresh evidence exists for this step → the side effect already happened and was captured →
 *   [PlanStepCommand.Prove] → [StepStatus.COMPLETED]. Never re-run.
 * - No fresh evidence → recovery genuinely cannot tell "nothing happened yet" from "something
 *   happened but the app died before recording it" (tool traces are never persisted — only
 *   [com.aiopenpocket.agent.core.plan.model.PlanEvidence] is) → [PlanStepCommand.Block] →
 *   [StepStatus.BLOCKED], surfaced to the user for manual review, exactly like a `BEFORE_PLAN_STEP`
 *   hook denial ([PlanExecutor.blockByHook]). This is deliberately MORE conservative than the
 *   plan's own Task 14 Step 1 sketch ("EXECUTING with no tool trace → READY if retry budget
 *   remains"): that row assumes recovery can tell "no side effect was even attempted", which the
 *   durable state this engine persists today cannot support in general (only [PlanStep.expectedTools]
 *   being empty would prove that — see "Deliberately not implemented" below) — see this task's own
 *   report for why blocking, not guessing, was chosen.
 *
 * Recovery is naturally idempotent without any extra bookkeeping: once a step leaves
 * EXECUTING/VERIFYING (to COMPLETED or BLOCKED, both stable/non-selectable statuses — [StepStatus]'s
 * own kdoc, [PlanRunner]'s `SELECTABLE_STATUSES`), a second [recover] pass no longer finds it in
 * either status and skips it entirely — no new [com.aiopenpocket.agent.core.plan.model.PlanEvidence]/
 * [PlanEvent] rows, no re-transition attempted. A cleanly-terminal plan
 * ([com.aiopenpocket.agent.core.plan.model.PlanStatus.COMPLETED]/`CANCELLED`/`FAILED`) is excluded at
 * the SOURCE (`PlanRepository.getNonTerminalPlans`) rather than filtered per-step, so [recover] never
 * even reads, let alone writes, a terminal plan's rows.
 *
 * ## Where this runs
 *
 * [recover] is the app-startup sweep (plan Task 14 Step 3: "Invoke once from app initialization
 * after Room is available") — best-effort across every non-terminal plan, with no foreground caller
 * in hand. [recoverSnapshot] is the SAME repair logic reused by [PlanRunner.runLocked] to self-heal
 * the ONE plan a live run is about to advance, BEFORE step selection — closing the gap between "app
 * cold-started, recovery already ran" and "process merely restarted a run for one specific plan"
 * without [PlanRunner] taking on its own [PlanRepository]/[PlanScheduleSync] pair (it already holds a
 * [PlanRepository]; [recoverSnapshot] is a plain top-level-ish function taking that same instance as
 * a parameter, not a second injected collaborator). Neither entry point ever starts foreground LLM
 * work — recovery only calls [PlanRepository]/[PlanStateMachine], never [PlanStepRunner]/`PlanVerifier`
 * (plan Task 14 Step 3: "Recovery never starts foreground LLM work automatically").
 *
 * ## [scheduler]: re-syncing, not selecting
 *
 * [scheduler] mirrors [PlanCommands]' own choice of the narrow [PlanScheduleSync] port rather than
 * the concrete `com.aiopenpocket.agent.core.plan.schedule.PlanScheduler` (bound to it via
 * `PlanSchedulerBindings`) — same "no WorkManager in a plain unit test" reasoning. [recover] calls
 * [PlanScheduleSync.sync] only for a plan [recoverSnapshot] actually changed (plan Task 14 Step 3:
 * "it repairs states and schedules due heartbeats") — a healthy plan with nothing stuck is never
 * touched, and [PlanRunner]'s own reuse of [recoverSnapshot] does not call [scheduler] at all (a live
 * run's loop re-selects a step itself in the very same call; the NEXT heartbeat is armed the way it
 * always was, by whatever already calls `PlanScheduleSync.sync` around that run).
 *
 * ## Deliberately not implemented (scope decisions, see this task's own report for detail)
 *
 * - No per-tool-call `executionId`/intent-then-completion event pair (plan Task 14 Step 2's more
 *   granular sketch): that would mean [com.aiopenpocket.agent.loop.ReactLoopRunner]'s `BEFORE_TOOL`/
 *   `AFTER_TOOL` hook firing appending [PlanEvent]s, which is out of this task's file list and far
 *   outside "scope to plan steps" for a hot path every agent mode shares. The
 *   ([PlanStep.id], [PlanStepState.attempts], [PlanStepState.startedAt]) triple this engine already
 *   persists is this task's minimal, in-scope stand-in: it is exactly as much "which attempt is
 *   this" identity as an `executionId` would add, without a new column/entity.
 * - No `expectedTools.isEmpty()` fast path back to [StepStatus.READY] for a step that structurally
 *   cannot have a side effect: correct in principle, but adds an untested-by-this-task branch for a
 *   convenience (skip one manual unblock) rather than a safety requirement — left as a documented,
 *   deliberately conservative simplification, not an oversight.
 * - No interstitial persisted [StepStatus.VERIFYING] row in [PlanExecutor.recordOutcome] (its own
 *   kdoc's "KNOWN GAP"): closing it fully would touch that method's core sequencing and its existing
 *   726-line test suite for a narrow window (a crash strictly inside `PlanVerifier.verify`'s own
 *   MODEL_ASSESSMENT LLM call). [recoverStep] already degrades SAFELY for that window today (no
 *   fresh evidence yet → BLOCKED, never a wrong/duplicated side effect) — just not to the more
 *   precise "rerun verifier only" outcome the design's prose describes.
 */
@SingleIn(AppScope::class)
class PlanRecovery @Inject constructor(
  private val repository: PlanRepository,
  private val scheduler: PlanScheduleSync,
) {
  private var clock: () -> Long = System::currentTimeMillis

  /** Test seam — mirrors every other Structured Plan Engine class's own secondary constructor for a fixed time source. */
  constructor(repository: PlanRepository, scheduler: PlanScheduleSync, clock: () -> Long) : this(repository, scheduler) {
    this.clock = clock
  }

  /**
   * The app-startup sweep — see class kdoc "Where this runs". Best-effort: one plan throwing (e.g. a
   * concurrent mutation) does not need to be caught here specifically, matching every other
   * Structured Plan Engine class's "throw rather than silently corrupt state" convention; a caller
   * that wants partial-failure isolation across plans can wrap each call site itself.
   *
   * [startedBefore] is the moment this process started: a step started after it is not a leftover of a
   * dead process but a live run (a WorkManager job can start one in the same process while this sweep
   * is still going), and blocking it would be a false repair.
   */
  suspend fun recover(startedBefore: Long = Long.MAX_VALUE) {
    for (plan in repository.getNonTerminalPlans()) {
      val snapshot = repository.snapshot(plan.id) ?: continue
      if (recoverSnapshot(repository, snapshot, clock(), startedBefore)) {
        scheduler.sync(plan.id)
      }
    }
  }

  companion object {
    /**
     * Repairs every [StepStatus.EXECUTING]/[StepStatus.VERIFYING] [PlanStepState] in [snapshot] —
     * see class kdoc. Reusable by [PlanRunner] (which already holds a [PlanRepository] of its own)
     * without [PlanRunner] taking on a [PlanRecovery]/[PlanScheduleSync] dependency purely to reach
     * this ONE plan's repair. Returns whether anything actually changed, so a caller knows whether
     * its OWN, now-stale, [snapshot] needs a fresh re-read.
     */
    internal suspend fun recoverSnapshot(
      repository: PlanRepository,
      snapshot: PlanSnapshot,
      now: Long,
      startedBefore: Long = Long.MAX_VALUE,
    ): Boolean {
      var changed = false
      for (state in snapshot.stepStates) {
        if (state.status != StepStatus.EXECUTING && state.status != StepStatus.VERIFYING) continue
        if ((state.startedAt ?: Long.MIN_VALUE) >= startedBefore) continue
        val step = snapshot.version.steps.find { it.id == state.stepId } ?: continue
        if (recoverStep(repository, snapshot, step, state, now)) changed = true
      }
      return changed
    }

    /**
     * One stuck step: [StepStatus.EXECUTING] always legally reaches [StepStatus.VERIFYING] first via
     * [PlanStepCommand.Finish] (`PlanStateMachine`'s own graph — the state machine never allows any
     * OTHER outgoing edge from EXECUTING), then the same evidence check [decideRecoveredStatus]
     * documents picks [PlanStepCommand.Prove] or [PlanStepCommand.Block] from VERIFYING. Mirrors
     * [PlanExecutor.recordOutcome]'s own accepted simplification of never persisting an interim
     * VERIFYING row — only [PlanStateMachine] validates that hop is legal; ONE final [PlanStepState]
     * is written either way. [state].status may already BE [StepStatus.VERIFYING] (defensive; not
     * reachable via [PlanExecutor] today — see class kdoc "Deliberately not implemented") — the
     * Finish hop is simply skipped in that case since it is already there.
     */
    private suspend fun recoverStep(
      repository: PlanRepository,
      snapshot: PlanSnapshot,
      step: PlanStep,
      state: PlanStepState,
      now: Long,
    ): Boolean {
      if (state.status == StepStatus.EXECUTING) {
        val toVerifying = PlanStateMachine.transitionStep(StepStatus.EXECUTING, PlanStepCommand.Finish)
        check(toVerifying is StepTransitionResult.Allowed) {
          "Unreachable: StepStatus.EXECUTING must legally allow Finish, but PlanStateMachine rejected it."
        }
      }

      val command = decideRecoveredStatus(snapshot, step, state)
      return when (val result = PlanStateMachine.transitionStep(StepStatus.VERIFYING, command)) {
        is StepTransitionResult.Allowed -> {
          persistRecovered(repository, snapshot, step.id, state, result.status, now)
          true
        }
        // Unreachable in practice (VERIFYING legally allows both Prove and Block) — kept only as the
        // same self-documenting assertion PlanVerifier.stepStatusFor makes for its own "always Allowed".
        is StepTransitionResult.Rejected -> false
      }
    }

    /**
     * The evidence gate — see class kdoc "Idempotency: evidence, not a guess, decides
     * complete-vs-review". Scoped to evidence created no earlier than THIS attempt's own
     * [PlanStepState.startedAt] so a PRIOR attempt's already-persisted (but insufficient-at-the-time)
     * evidence is never mistaken for proof that the CURRENT, crashed attempt's own side effect ran.
     */
    private fun decideRecoveredStatus(snapshot: PlanSnapshot, step: PlanStep, state: PlanStepState): PlanStepCommand {
      val attemptStartedAt = state.startedAt ?: 0L
      val hasFreshEvidence = snapshot.evidence.any { it.stepId == step.id && it.createdAt >= attemptStartedAt }
      return if (hasFreshEvidence) PlanStepCommand.Prove else PlanStepCommand.Block(NO_EVIDENCE_REASON)
    }

    private suspend fun persistRecovered(
      repository: PlanRepository,
      snapshot: PlanSnapshot,
      stepId: String,
      previous: PlanStepState,
      newStatus: StepStatus,
      now: Long,
    ) {
      repository.updateStepState(
        PlanStepState(
          planId = snapshot.plan.id,
          version = snapshot.version.version,
          stepId = stepId,
          status = newStatus,
          attempts = previous.attempts,
          nextDueAt = previous.nextDueAt,
          startedAt = previous.startedAt,
          completedAt = if (newStatus == StepStatus.COMPLETED) now else null,
        ),
      )
      val eventType = if (newStatus == StepStatus.BLOCKED) PlanEventType.STEP_BLOCKED else PlanEventType.STEP_VERIFIED
      repository.appendEvent(
        PlanEvent(
          id = UUID.randomUUID().toString(),
          planId = snapshot.plan.id,
          version = snapshot.version.version,
          stepId = stepId,
          type = eventType,
          payload = buildJsonObject {
            put("recovered", true)
            put("status", newStatus.name)
            if (newStatus == StepStatus.BLOCKED) put("reason", NO_EVIDENCE_REASON)
          },
          createdAt = now,
        ),
      )
    }

    private const val NO_EVIDENCE_REASON =
      "Recovered after the app process died mid-step: left EXECUTING with no confirmed evidence, so " +
        "a possible side-effecting tool call was NOT re-run. Needs manual review (retry/skip/revise)."
  }
}
