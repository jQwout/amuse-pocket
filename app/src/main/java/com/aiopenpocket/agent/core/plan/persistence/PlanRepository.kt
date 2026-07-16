package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.withTransaction
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanEvent
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStepState
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.validation.PlanCommand
import com.aiopenpocket.agent.core.plan.validation.PlanStateMachine
import com.aiopenpocket.agent.core.plan.validation.PlanTransitionResult
import com.aiopenpocket.agent.core.plan.validation.PlanVersionResult
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Transactional Room-backed store for the Structured Plan Engine's five tables (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 6) — [AgentPlanEntity]/[PlanVersionEntity]/
 * [PlanStepStateEntity]/[PlanEvidenceEntity]/[PlanEventEntity], all behind [PlanDao].
 *
 * ## Status is always state-machine output, never a hand-picked value
 *
 * Every write to [AgentPlanEntity.status] here is the RESULT of
 * [PlanStateMachine.transition]/[PlanStateMachine.approveVersion] — this class never assigns a
 * [PlanStatus] directly, mirroring how [com.aiopenpocket.agent.core.agent.UserAgentRepository] never
 * writes [com.aiopenpocket.agent.core.agent.AgentStatus] except via [com.aiopenpocket.agent.core.agent.AgentValidator].
 * An illegal transition throws (`error`/`check`/`require` — matching [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish]'s
 * own "throw and leave every row untouched" convention) rather than silently no-op'ing.
 *
 * ## Immutability
 *
 * [saveProposal] refuses (throws) to write a ([AgentPlan.id], [PlanVersion.version]) row that already
 * exists AND is approved — the one and only Global Constraint this whole engine exists to protect
 * ("approved PlanVersion is immutable"). [approve] itself only ever stamps `approvedAt` on an
 * unapproved row (via [PlanStateMachine.approveVersion], which itself refuses to re-freeze an
 * already-approved version) — it never rewrites `snapshotJson`. A material revision therefore always
 * lands on a NEW, higher version number: when [saveProposal] is called while the persisted status is
 * [PlanStatus.ACTIVE]/[PlanStatus.PAUSED] (a revision proposed mid-execution), it folds in
 * [PlanCommand.MaterialChangeDetected] before [PlanCommand.ProposalValidated] so a caller (Task 4/5/10's
 * planner) never needs a separate "flag material change" round-trip first — see [resolveProposalStatus].
 *
 * ## One active execution per conversation
 *
 * [approve] refuses if [AgentPlan.conversationId] already runs a DIFFERENT plan in [PlanStatus.ACTIVE]
 * (design spec sections 2/10: "один conversation исполняет не более одного plan run одновременно"). A
 * conversation may still hold many plans in other (non-ACTIVE) statuses.
 *
 * ## What this class deliberately does NOT do
 *
 * It never appends a [PlanEvent] on its own initiative. [appendEvent] is the sole write path for the
 * event log; [saveProposal]/[approve]/[requestRevision]/[pause]/[resume]/[cancel] only ever move
 * [AgentPlan]/[PlanVersion] rows. Automatic lifecycle event emission tied to hook lifecycle points
 * (`ON_PLAN_CREATED`/`ON_PLAN_APPROVED`/...) is explicitly Task 9's job ("Add plan lifecycle events to
 * HookEngine") — baking that in here now would risk double-logging once Task 9 wires its own emission
 * on top of this same [appendEvent]. [requestRevision]'s `feedback` string is likewise not durably
 * stored by this class — it is handed to the planner (Task 4/5), which decides what (if anything) to
 * persist about it; call [appendEvent] alongside it if an audit trail entry is wanted.
 *
 * ## Surface beyond the plan's literal Task 3 interface sketch
 *
 * [getVersions]/[getEvents]/[observeEvents] are not in the design doc's inline `interface PlanRepository`
 * sketch, which only shows [snapshot]/[observe] reading the CURRENT (approved-or-pending) version.
 * They are still necessary: a plan detail/timeline UI (Task 12) needs the full version history and the
 * event log, and this task's own instructions explicitly ask for "Flow observers for progress/versions/
 * events" — without a read accessor at all, `plan_events` would be a write-only table. Kept minimal
 * (plain ordered list reads) so later tasks can layer richer queries on top without reworking this one.
 *
 * [clock] mirrors [com.aiopenpocket.agent.core.agent.UserAgentRepository]'s own reasoning for not being
 * a plain `@Inject` binding: a bare `() -> Long` would be untyped/ambiguous across the whole app graph.
 * The secondary constructor lets tests substitute a fixed time source for deterministic `createdAt`/
 * `approvedAt`/`updatedAt` assertions without changing what Metro sees.
 */
@SingleIn(AppScope::class)
class PlanRepository @Inject constructor(
  private val database: AppDatabase,
  private val dao: PlanDao,
) {
  private var clock: () -> Long = System::currentTimeMillis

  constructor(database: AppDatabase, dao: PlanDao, clock: () -> Long) : this(database, dao) {
    this.clock = clock
  }

  /**
   * The plan's LATEST version — the pending draft under review while [PlanStatus.AWAITING_APPROVAL],
   * otherwise whatever is [AgentPlan.approvedVersion] (the two coincide outside a pending revision,
   * since [saveProposal] is the only thing that ever adds a version row, and it always drives status to
   * [PlanStatus.AWAITING_APPROVAL] in the same transaction). Matches [PlanSnapshot]'s own kdoc:
   * "typically the approved one; the pending one while awaiting approval" — design spec section 8 wants
   * the PlanCard to show the candidate under review (diffed against the approved one), not stale
   * approved content, while a revision is pending. [PlanSnapshot.stepStates]/[PlanSnapshot.evidence] are
   * scoped to that SAME version number. Null if [planId] does not exist.
   */
  suspend fun snapshot(planId: String): PlanSnapshot? = dao.getPlan(planId)?.let { toSnapshot(it) }

  fun observe(planId: String): Flow<PlanSnapshot?> = dao.observePlan(planId).map { entity -> entity?.let { toSnapshot(it) } }

  fun observeByConversation(conversationId: String): Flow<List<PlanSnapshot>> =
    dao.observePlansByConversation(conversationId).map { plans -> plans.mapNotNull { toSnapshot(it) } }

  fun observeByAgent(agentId: String): Flow<List<PlanSnapshot>> =
    dao.observePlansByAgent(agentId).map { plans -> plans.mapNotNull { toSnapshot(it) } }

  /**
   * Every plan not yet in a terminal [PlanStatus] (design spec section 4) — Task 14's
   * `com.aiopenpocket.agent.core.plan.execution.PlanRecovery.recover`'s own candidate list for its
   * app-startup sweep. Not part of Task 3's original surface (that task's [observeByConversation]/
   * [observeByAgent] both need a caller-supplied scope); added here because a process-death recovery
   * pass runs with no conversation/agent in hand and needs to enumerate literally every plan that
   * COULD have a step left stuck EXECUTING/VERIFYING.
   */
  suspend fun getNonTerminalPlans(): List<AgentPlan> = dao.getNonTerminalPlans().map { it.toDomain() }

  /** Full version history for [planId], oldest first — includes superseded/never-approved drafts. */
  suspend fun getVersions(planId: String): List<PlanVersion> = dao.getVersionsForPlan(planId).map { it.toDomain() }

  /** Every [PlanEvent] for [planId], oldest first. */
  suspend fun getEvents(planId: String): List<PlanEvent> = dao.getEvents(planId).map { it.toDomain() }

  /** Live view of [getEvents]. */
  fun observeEvents(planId: String): Flow<List<PlanEvent>> = dao.observeEvents(planId).map { rows -> rows.map { it.toDomain() } }

  /**
   * Persists a brand-new plan's first version, or (when [plan].id already names an existing row) a
   * subsequent proposal/revision. The CURRENT status used to gate this call is always whatever is
   * PERSISTED for an existing plan — never [plan]'s own [AgentPlan.status] field, which this method
   * ignores for that purpose (see [resolveProposalStatus]). Throws if no legal path to
   * [PlanStatus.AWAITING_APPROVAL] exists from the current status, if [version] does not belong to
   * [plan], if [version] arrives pre-approved, or if ([plan].id, [version].version) already names an
   * APPROVED row (immutability). Never touches an existing row's [AgentPlan.approvedVersion]/
   * [AgentPlan.activeStepId] — those change only via [approve]/[updateStepState]'s callers.
   */
  suspend fun saveProposal(plan: AgentPlan, version: PlanVersion) {
    require(version.planId == plan.id) {
      "PlanVersion.planId '${version.planId}' does not match AgentPlan.id '${plan.id}'."
    }
    require(version.approvedAt == null) { "A freshly proposed version must not already be approved." }

    database.withTransaction {
      val existingPlan = dao.getPlan(plan.id)
      val currentStatus = existingPlan?.let { PlanStatus.valueOf(it.status) } ?: plan.status
      val nextStatus = resolveProposalStatus(currentStatus, plan.id)

      val existingVersion = dao.getVersion(plan.id, version.version)
      check(existingVersion == null || existingVersion.approvedAt == null) {
        "Version ${version.version} of plan '${plan.id}' is already approved; a material revision must use a new version number."
      }

      val now = clock()
      // План — ПЕРЕД версией: plan_versions.planId ссылается на agent_plans.id, и у нового плана
      // родительской строки ещё нет — обратный порядок роняет вставку по FK.
      dao.upsertPlan(
        AgentPlanEntity(
          id = plan.id,
          agentId = plan.agentId,
          conversationId = plan.conversationId,
          horizon = plan.horizon.name,
          status = nextStatus.name,
          approvedVersion = existingPlan?.approvedVersion,
          activeStepId = existingPlan?.activeStepId,
          createdAt = existingPlan?.createdAt ?: plan.createdAt,
          updatedAt = now,
        ),
      )
      dao.upsertVersion(PlanVersionEntity.fromDomain(version))
    }
  }

  /**
   * Approves [version] of [planId]. Throws unless [version] is the plan's current PENDING (highest,
   * unapproved) version — "wrong pending version cannot be approved" — and unless [planId]'s
   * conversation has no OTHER plan currently [PlanStatus.ACTIVE] — "one active execution per
   * conversation". Returns the resulting [PlanSnapshot].
   */
  suspend fun approve(planId: String, version: Int): PlanSnapshot = database.withTransaction {
    val planEntity = requireNotNull(dao.getPlan(planId)) { "Unknown plan id: $planId" }
    val currentStatus = PlanStatus.valueOf(planEntity.status)
    val nextStatus = allowedStatus(currentStatus, PlanCommand.Approve) {
      "Cannot approve plan '$planId' from status $currentStatus: $it"
    }

    val pendingVersion = dao.getMaxVersion(planId)
    check(version == pendingVersion) {
      "Version $version is not the pending version of plan '$planId' (pending is $pendingVersion)."
    }
    val versionEntity = requireNotNull(dao.getVersion(planId, version)) {
      "Version $version does not exist for plan '$planId'."
    }
    check(versionEntity.approvedAt == null) { "Version $version of plan '$planId' is already approved." }

    check(dao.countOtherActiveInConversation(planEntity.conversationId, excludePlanId = planId) == 0) {
      "Conversation '${planEntity.conversationId}' already runs another ACTIVE plan; only one plan may execute per conversation."
    }

    val now = clock()
    val approvedVersion = when (val result = PlanStateMachine.approveVersion(versionEntity.toDomain(), approvedAt = now)) {
      is PlanVersionResult.Allowed -> result.version
      is PlanVersionResult.Rejected -> error("Cannot approve plan '$planId' version $version: ${result.reason}")
    }
    dao.upsertVersion(PlanVersionEntity.fromDomain(approvedVersion))
    dao.upsertPlan(planEntity.copy(status = nextStatus.name, approvedVersion = version, updatedAt = now))

    requireNotNull(toSnapshot(requireNotNull(dao.getPlan(planId)))) { "Plan '$planId' vanished mid-approval." }
  }

  /**
   * Sends [planId] back to [PlanStatus.DRAFT] so its planner can re-propose. [feedback] is NOT itself
   * durably stored by this call — see class kdoc "What this class deliberately does NOT do".
   */
  suspend fun requestRevision(planId: String, feedback: String) {
    applySimpleTransition(planId, PlanCommand.RequestChanges(feedback))
  }

  suspend fun pause(planId: String) = applySimpleTransition(planId, PlanCommand.Pause)

  suspend fun resume(planId: String) = applySimpleTransition(planId, PlanCommand.Resume)

  suspend fun cancel(planId: String) = applySimpleTransition(planId, PlanCommand.Cancel)

  /**
   * Attempts [PlanStatus.ACTIVE] -> [PlanStatus.COMPLETED] via [PlanStateMachine.tryComplete]'s
   * evidence gate (design spec section 4/11) — the whole-plan analogue of `PlanExecutor`'s own
   * per-step verification (`PlanVerifier`), called by
   * `com.aiopenpocket.agent.core.plan.execution.PlanRunner` (Task 10) once a run finds every step
   * COMPLETED/SKIPPED. `PlanExecutor.recordOutcome`'s own kdoc explicitly leaves this write to
   * "PlanRunner, Task 10" ("Whole-plan completion is normally PlanRunner's call... PlanStatus.COMPLETED
   * remains exclusively PlanStateMachine's to assign") — before this method, nothing in the codebase
   * could ever persist [PlanStatus.COMPLETED] at all.
   *
   * Unlike [approve]/[pause]/[resume]/[cancel]/[requestRevision] (which THROW on an illegal command),
   * a not-yet-satisfied evidence gate or a non-ACTIVE current status is NOT an error here —
   * [tryComplete] is meant to be probed opportunistically after every step, and "not ready yet" is the
   * ordinary case, not a caller mistake (mirrors [PlanStateMachine.tryComplete]'s own non-throwing
   * [PlanTransitionResult.Rejected] for that same reason). Returns [planId]'s current [PlanSnapshot]
   * either way (transitioned or not) — the caller tells which happened by comparing
   * [PlanSnapshot.plan]'s status to what it was before calling this. No-op (snapshot unchanged) when
   * [planId] has no [AgentPlan.approvedVersion] yet — an unapproved plan can never be this method's
   * concern.
   */
  suspend fun tryComplete(planId: String): PlanSnapshot = database.withTransaction {
    val planEntity = requireNotNull(dao.getPlan(planId)) { "Unknown plan id: $planId" }
    val currentStatus = PlanStatus.valueOf(planEntity.status)
    val versionNumber = planEntity.approvedVersion
    if (versionNumber != null) {
      val versionEntity = dao.getVersion(planId, versionNumber)
      if (versionEntity != null) {
        val criteria = versionEntity.toDomain().criteria
        val evidence = dao.getEvidence(planId, versionNumber).map { it.toDomain() }
        val result = PlanStateMachine.tryComplete(currentStatus, criteria, evidence)
        if (result is PlanTransitionResult.Allowed) {
          dao.upsertPlan(planEntity.copy(status = result.status.name, updatedAt = clock()))
        }
      }
    }
    requireNotNull(toSnapshot(requireNotNull(dao.getPlan(planId)))) { "Plan '$planId' vanished mid-completion-check." }
  }

  /** Non-material (design spec section 6): persists whatever next [PlanStepState] the caller computed — this method does not itself call `PlanStateMachine.transitionStep`, since the caller already did. */
  suspend fun updateStepState(state: PlanStepState) = database.withTransaction {
    dao.upsertStepState(PlanStepStateEntity.fromDomain(state))
    dao.touchPlan(state.planId, clock())
  }

  /** Non-material, append-mostly. */
  suspend fun addEvidence(evidence: PlanEvidence) = database.withTransaction {
    dao.insertEvidence(PlanEvidenceEntity.fromDomain(evidence))
    dao.touchPlan(evidence.planId, clock())
  }

  /** The sole write path for `plan_events` — see class kdoc "What this class deliberately does NOT do". */
  suspend fun appendEvent(event: PlanEvent) = database.withTransaction {
    dao.insertEvent(PlanEventEntity.fromDomain(event))
    dao.touchPlan(event.planId, clock())
  }

  private suspend fun applySimpleTransition(planId: String, command: PlanCommand) {
    database.withTransaction {
      val planEntity = requireNotNull(dao.getPlan(planId)) { "Unknown plan id: $planId" }
      val currentStatus = PlanStatus.valueOf(planEntity.status)
      val nextStatus = allowedStatus(currentStatus, command) {
        "Cannot apply $command to plan '$planId' from status $currentStatus: $it"
      }
      dao.upsertPlan(planEntity.copy(status = nextStatus.name, updatedAt = clock()))
    }
  }

  /**
   * [PlanCommand.ProposalValidated] directly when legal ([PlanStatus.DRAFT]/[PlanStatus.NEEDS_REVISION]).
   * When [current] is [PlanStatus.ACTIVE]/[PlanStatus.PAUSED] — a material revision proposed while the
   * plan is executing — first folds in [PlanCommand.MaterialChangeDetected] (design spec section 4:
   * ACTIVE/PAUSED -> NEEDS_REVISION on a material revision) before applying
   * [PlanCommand.ProposalValidated], so [saveProposal] alone is what a later planner/PlanRunner (Task
   * 4/5/10) calls for BOTH a first-time draft and a material revision — it never needs its own separate
   * "flag material change" repository round-trip first.
   */
  private fun resolveProposalStatus(current: PlanStatus, planId: String): PlanStatus {
    val direct = PlanStateMachine.transition(current, PlanCommand.ProposalValidated)
    if (direct is PlanTransitionResult.Allowed) return direct.status

    if (current == PlanStatus.ACTIVE || current == PlanStatus.PAUSED) {
      val flagged = PlanStateMachine.transition(current, PlanCommand.MaterialChangeDetected("New plan version proposed"))
      if (flagged is PlanTransitionResult.Allowed) {
        val proposed = PlanStateMachine.transition(flagged.status, PlanCommand.ProposalValidated)
        if (proposed is PlanTransitionResult.Allowed) return proposed.status
      }
    }
    error("Cannot save a plan proposal for plan '$planId' from status $current.")
  }

  private fun allowedStatus(current: PlanStatus, command: PlanCommand, lazyMessage: (String) -> String): PlanStatus =
    when (val result = PlanStateMachine.transition(current, command)) {
      is PlanTransitionResult.Allowed -> result.status
      is PlanTransitionResult.Rejected -> error(lazyMessage(result.error.reason))
    }

  /** The single "which version does a snapshot show" policy: always the LATEST version row — see [snapshot]'s kdoc for why that is also "the approved one" outside a pending revision. Null only if [planEntity] somehow has zero version rows. */
  private suspend fun toSnapshot(planEntity: AgentPlanEntity): PlanSnapshot? {
    val versionNumber = dao.getMaxVersion(planEntity.id) ?: return null
    val versionEntity = dao.getVersion(planEntity.id, versionNumber) ?: return null
    return PlanSnapshot(
      plan = planEntity.toDomain(),
      version = versionEntity.toDomain(),
      stepStates = dao.getStepStates(planEntity.id, versionNumber).map { it.toDomain() },
      evidence = dao.getEvidence(planEntity.id, versionNumber).map { it.toDomain() },
    )
  }
}
