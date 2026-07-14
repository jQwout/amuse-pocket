package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.util.UUID

/**
 * Background/heartbeat hook for a plan's [com.aiopenpocket.agent.core.plan.model.PlanStep.schedule]
 * (design spec `2026-07-12-structured-plan-engine-design.md` section 11) — kept as a narrow port
 * (mirrors [com.aiopenpocket.agent.loop.TurnExecutor]/[com.aiopenpocket.agent.loop.TurnPersistence]'s
 * own reasoning for a seam interface) so [PlanCommands] itself never depends on WorkManager and stays
 * unit-testable with a plain fake. [sync] (re)arms whatever due/periodic work an ACTIVE plan's steps
 * need; [cancel] tears it down — both idempotent from the caller's point of view.
 *
 * Task 5 binds [providePlanScheduleSync] below to a no-op — nothing schedules yet, so there is
 * nothing to arm or cancel. Task 11 ("Refactor heartbeat scheduling into a typed PlanScheduler")
 * replaces just that one binding with a WorkManager-backed implementation; [PlanCommands]' call
 * sites do not change.
 */
interface PlanScheduleSync {
  suspend fun sync(planId: String)
  suspend fun cancel(planId: String)
}

/**
 * The ONLY way a user (or, later, Plan UI/Task 12) may move a [com.aiopenpocket.agent.core.plan.model.AgentPlan]
 * through approve/revise/pause/resume/cancel — a thin, typed wrapper over [PlanRepository] that
 * replaces the Фаза A hack of simulating approval with a magic chat message (plan's Global
 * Constraints: "Approval — typed repository command. Не сохранять magic user message «Одобряю
 * план...»"; design spec section 8: "Одобрение не симулируется user-сообщением «Одобряю план,
 * выполни его»; это typed intent к PlanRepository"). No [TurnManager][com.aiopenpocket.agent.loop.TurnManager]
 * turn is started by any method here — per design spec section 9, approval "остаётся в Plan UI, но
 * не запускает исполнение автоматически"; GOAL separately picks up the approved version on its own
 * turn (Task 10).
 *
 * Every method here either fully succeeds or throws with every row untouched — same convention as
 * [PlanRepository] itself (see its class kdoc) — because [approve]/[requestChanges] are guarded by
 * an explicit version check (below) before anything is written, and [pause]/[resume]/[cancel]
 * delegate straight to [PlanRepository]'s own [PlanStateMachine][com.aiopenpocket.agent.core.plan.validation.PlanStateMachine]-backed
 * transitions, which have the same all-or-nothing behavior.
 *
 * ## Why [approve]/[requestChanges] take an explicit `version`
 *
 * A caller (the chat screen, Plan UI) always names the exact [com.aiopenpocket.agent.core.plan.model.PlanVersion.version]
 * it is currently showing the user — never "whatever is pending right now". [approve] delegates
 * that check to [PlanRepository.approve] itself (which already refuses a non-pending version —
 * see its kdoc "wrong pending version cannot be approved"); [requestChanges] performs the
 * equivalent check itself because [PlanRepository.requestRevision] takes no version parameter at
 * all (it only flips [com.aiopenpocket.agent.core.plan.model.PlanStatus.AWAITING_APPROVAL] back to
 * [com.aiopenpocket.agent.core.plan.model.PlanStatus.DRAFT]). Without this guard a UI showing a
 * stale (superseded) draft — e.g. a material revision landed while the user was reading an older
 * version — could silently approve/revise content the user never actually saw.
 *
 * ## Background callers cannot fabricate an approval
 *
 * There is no headless/background entry point into [approve] anywhere in this class or its
 * callers: it is only ever invoked from a user-driven `ChatIntent`/Plan UI action (see
 * `com.aiopenpocket.ui.chat.ChatViewModel`). A heartbeat/background run (design spec section 9:
 * "Background run не ждёт UI approval") can execute an already-*approved* plan's next step, but has
 * no path that calls this class to *create* that approval.
 */
@SingleIn(AppScope::class)
class PlanCommands @Inject constructor(
  private val repository: PlanRepository,
  private val scheduler: PlanScheduleSync,
  private val hookRepository: AgentHookRepository,
) {

  /**
   * Approves [version] of [planId] — freezes it immutable and moves the plan to
   * [com.aiopenpocket.agent.core.plan.model.PlanStatus.ACTIVE]. Throws (via [PlanRepository.approve])
   * if [version] is not the plan's current pending version — approving a stale/superseded draft is
   * always rejected, never silently redirected to "the latest one instead". On success, arms
   * [scheduler] for this plan (a no-op today — see [PlanScheduleSync] kdoc) and fires
   * [HookEvent.ON_PLAN_APPROVED] (Task 9) — see [firePlanApproved].
   */
  suspend fun approve(planId: String, version: Int) {
    val snapshot = repository.approve(planId, version)
    scheduler.sync(planId)
    firePlanApproved(snapshot)
  }

  /**
   * Fires [HookEvent.ON_PLAN_APPROVED] for the agent [snapshot] belongs to, if any. LOG-only per the
   * action matrix ([com.aiopenpocket.agent.core.hook.HookEngine.actionsAllowedFor]), so this is
   * purely an audit-journal side effect via `firePlanHook` (`PlanHookContext.kt`) — [approve] never
   * branches on its result. Unlike [PlanExecutor], which reuses an already-loaded
   * [com.aiopenpocket.agent.core.agent.AgentDefinition]'s own hook list, [PlanCommands] never loads a
   * full [com.aiopenpocket.agent.core.agent.AgentDefinition] (only a `planId`/`version`), so this
   * fetches the declarative hook set straight from [hookRepository] instead — same data, cheaper
   * than resolving a whole agent just to reach its `.hooks` field. No-op for a plan with no
   * [com.aiopenpocket.agent.core.plan.model.AgentPlan.agentId] (a conversation with no bound agent —
   * mirrors `ReactLoopRunner`'s own "plain chat never touches the hook engine" precedent) or for an
   * agent with no hooks configured (`firePlanHook`'s own short-circuit).
   */
  private suspend fun firePlanApproved(snapshot: PlanSnapshot) {
    val agentId = snapshot.plan.agentId ?: return
    firePlanHook(
      hookRepository = hookRepository,
      hooks = hookRepository.getForAgent(agentId),
      event = HookEvent.ON_PLAN_APPROVED,
      context = PlanHookContext(
        planId = snapshot.plan.id,
        planVersion = snapshot.version.version,
        planStatus = snapshot.plan.status,
      ),
      conversationId = snapshot.plan.conversationId,
      runId = UUID.randomUUID().toString(),
    )
  }

  /**
   * Sends [planId] back to [com.aiopenpocket.agent.core.plan.model.PlanStatus.DRAFT] with [feedback]
   * for the planner to fold into its next proposal (a NEW, higher-numbered version — this call
   * itself does not create one; see [PlanRepository.requestRevision]'s kdoc). [version] must be the
   * plan's current pending version, checked here (not by [PlanRepository], which takes no version
   * for this operation) — same "no acting on a stale draft" guarantee as [approve].
   */
  suspend fun requestChanges(planId: String, version: Int, feedback: String) {
    val snapshot = requireNotNull(repository.snapshot(planId)) { "Unknown plan id: $planId" }
    val pendingVersion = snapshot.version.version
    check(version == pendingVersion) {
      "Version $version is not the pending version of plan '$planId' (pending is $pendingVersion); " +
        "cannot request changes on a superseded draft."
    }
    repository.requestRevision(planId, feedback)
  }

  /** Pauses an ACTIVE plan and cancels any armed [scheduler] work for it. */
  suspend fun pause(planId: String) {
    repository.pause(planId)
    scheduler.cancel(planId)
  }

  /** Resumes a PAUSED plan and re-arms [scheduler] for it. */
  suspend fun resume(planId: String) {
    repository.resume(planId)
    scheduler.sync(planId)
  }

  /** Cancels the plan outright (terminal) and tears down any [scheduler] work for it. */
  suspend fun cancel(planId: String) {
    repository.cancel(planId)
    scheduler.cancel(planId)
  }
}

/** Task 5's binding for [PlanScheduleSync] — see that interface's kdoc for why it is a no-op until Task 11. */
@ContributesTo(AppScope::class)
@BindingContainer
object PlanScheduleSyncBindings {

  @Provides
  @SingleIn(AppScope::class)
  fun providePlanScheduleSync(): PlanScheduleSync = object : PlanScheduleSync {
    override suspend fun sync(planId: String) = Unit
    override suspend fun cancel(planId: String) = Unit
  }
}
