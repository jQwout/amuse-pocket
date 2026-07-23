package com.aiopenpocket.agent.loop

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.plan.execution.PlanHookContext
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.plan.execution.PlanRunRequest
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunner
import com.aiopenpocket.agent.core.plan.execution.firePlanHook
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.planner.PlanPlanner
import com.aiopenpocket.agent.core.plan.planner.PlanProposalResult
import com.aiopenpocket.agent.core.plan.template.PlanTemplateRegistry
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Drives [AgentMode.PLAN]/[AgentMode.GOAL] through the Structured Plan Engine (design spec
 * `2026-07-12-structured-plan-engine-design.md`, plan Task 10) — the same role
 * [ReflectionOrchestrator] plays for [AgentMode.REFLECT]: `com.aiopenpocket.di.TurnModule`'s
 * `TurnExecutor` dispatches straight here instead of through [AgentLoopOrchestrator]'s prompt-only
 * gate/loop (see [AgentMode]'s own kdoc for that routing change).
 *
 * ## [propose] — the PLAN turn
 *
 * Runs exactly one [PlanPlanner.propose] call (never a loop — see that class's own kdoc for why),
 * then:
 * - [PlanProposalResult.Proposed] → persists via [PlanRepository.saveProposal] (this is the FIRST
 *   real caller of that method outside tests — see [firePlanCreated] for why it also closes Task 9's
 *   `HookEvent.ON_PLAN_CREATED` gap) and emits [AgentEvent.PlanProposed]. This does NOT itself touch
 *   `com.aiopenpocket.ui.chat.ChatUiState.pendingPlanApproval` — `com.aiopenpocket.ui.chat.ChatViewModel`
 *   derives that reactively from [PlanRepository.observeByConversation] instead (see its own kdoc),
 *   so the approval bar shows correctly even if this event is missed/the screen wasn't open.
 * - [PlanProposalResult.NeedsInfo] → nothing is persisted (design's own state machine has no
 *   plan row for a question with no draft content yet); emits [AgentEvent.PlanBlocked] carrying the
 *   question as [AgentEvent.PlanBlocked.reason] so it persists as the assistant's next chat message,
 *   the same shape the old `[[NEED_INFO]]` marker turn had.
 * - [PlanProposalResult.Invalid]/[PlanProposalResult.Failed] → [AgentEvent.Error] (a real
 *   app/LLM-facing failure, unlike NeedsInfo).
 *
 * ## [executeGoal] — the GOAL turn
 *
 * Resolves which plan this conversation means (an explicit [planId], or the conversation's own
 * most-recently-touched non-terminal plan) and either delegates to [PlanRunner] for one bounded run,
 * or — design spec section 9: "Если пользователь включает GOAL без approved plan, runtime сначала
 * создаёт plan proposal" — falls back to [propose] when the conversation has no plan at all yet.
 * [userMessage] is only ever used by that fallback path; once a plan exists, [PlanRunner]/
 * `PlanExecutor` build the whole step prompt from persisted plan data, never from ad hoc chat text
 * (see `PlanExecutor.buildStepMessages`'s own kdoc) — this is this task's own necessary deviation
 * from the plan's illustrative `executeGoal(conversationId, planId, provider)` sketch (no
 * `userMessage`), flagged in the Task 10 report.
 *
 * ## Why this class never emits a raw [AgentEvent.TextDelta]
 *
 * Neither [PlanPlanner.propose] nor `PlanExecutor.execute` streams intermediate tokens out (the
 * latter's own [com.aiopenpocket.agent.core.plan.execution.PlanStepRunner] call passes a no-op
 * `emit`) — both are deliberately batched, single-shot calls. The chat UI still shows a loading
 * state the whole time via `TurnStatus.Running` (set before this flow is even collected), then the
 * final [AgentEvent.PlanProposed]/[AgentEvent.PlanProgressed]/[AgentEvent.PlanBlocked]/
 * [AgentEvent.Error] lands as one message — no partial/streaming text for a plan turn, by design.
 */
@SingleIn(AppScope::class)
class StructuredPlanOrchestrator @Inject constructor(
  private val planner: PlanPlanner,
  private val planRunner: PlanRunner,
  private val repository: PlanRepository,
  private val conversationRepository: ConversationRepository,
  private val templateRegistry: PlanTemplateRegistry,
  private val hookRepository: AgentHookRepository,
  private val log: AgentLog,
) {
  private var clock: () -> Long = System::currentTimeMillis

  /** Test seam — mirrors every other Structured Plan Engine class's own secondary constructor for a fixed time source. */
  constructor(
    planner: PlanPlanner,
    planRunner: PlanRunner,
    repository: PlanRepository,
    conversationRepository: ConversationRepository,
    templateRegistry: PlanTemplateRegistry,
    hookRepository: AgentHookRepository,
    log: AgentLog,
    clock: () -> Long,
  ) : this(planner, planRunner, repository, conversationRepository, templateRegistry, hookRepository, log) {
    this.clock = clock
  }

  fun propose(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, "Planning failed") {
    send(AgentEvent.StateChanged(AgentState.STREAMING))
    val agentId = conversationRepository.getConversation(conversationId)?.agentId
    val target = resolveDraftTarget(conversationId, agentId)
    log.d(TAG, "▶ PLAN propose conv=$conversationId plan=${target.planId} revision=${target.existingVersion != null}")

    val result = planner.propose(
      planId = target.planId,
      agentId = agentId,
      horizon = target.horizon,
      userMessage = userMessage,
      existingVersion = target.existingVersion,
      provider = provider,
    )
    send(AgentEvent.StateChanged(AgentState.FINISHED))

    when (result) {
      is PlanProposalResult.Proposed -> {
        val isBrandNew = target.existingVersion == null
        val plan = AgentPlan(
          id = target.planId,
          agentId = agentId,
          conversationId = conversationId,
          horizon = target.horizon,
          // Ignored by saveProposal for an EXISTING plan (it re-reads the persisted status itself —
          // see that method's own kdoc); DRAFT is the correct "as if it already existed" seed for a
          // brand-new one, since ProposalValidated from DRAFT is exactly what lands on
          // AWAITING_APPROVAL below.
          status = PlanStatus.DRAFT,
          approvedVersion = null,
          activeStepId = null,
          createdAt = clock(),
          updatedAt = clock(),
        )
        repository.saveProposal(plan, result.version)
        if (isBrandNew) firePlanCreated(plan, result.version, agentId)
        log.d(TAG, "✓ plan ${plan.id} v${result.version.version} awaiting approval")
        send(AgentEvent.PlanProposed(target.planId, result.version.version))
      }

      is PlanProposalResult.NeedsInfo -> {
        log.d(TAG, "plan ${target.planId} needs info: ${result.question.take(80)}")
        send(AgentEvent.PlanBlocked(target.planId, result.question))
      }

      is PlanProposalResult.Invalid -> {
        log.w(TAG, "plan ${target.planId} invalid: ${result.issues.size} issue(s)")
        send(AgentEvent.StateChanged(AgentState.ERROR))
        send(AgentEvent.Error("План не прошёл проверку: " + result.issues.joinToString("; ") { "${it.path}: ${it.message}" }))
      }

      is PlanProposalResult.Failed -> {
        log.w(TAG, "plan ${target.planId} proposal failed: ${result.message}")
        send(AgentEvent.StateChanged(AgentState.ERROR))
        send(AgentEvent.Error(result.message))
      }
    }
  }

  fun executeGoal(
    conversationId: String,
    planId: String?,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
    budget: PlanRunBudget = PlanRunBudget(),
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, "Goal execution failed") {
    send(AgentEvent.StateChanged(AgentState.STREAMING))
    val resolvedPlanId = planId ?: resolveExistingPlanId(conversationId)

    if (resolvedPlanId == null) {
      // Design spec section 9: "Если пользователь включает GOAL без approved plan, runtime сначала
      // создаёт plan proposal и останавливается на AWAITING_APPROVAL." propose() already owns the
      // whole error/event contract for that path, so this simply forwards its events verbatim.
      log.d(TAG, "GOAL conv=$conversationId has no plan yet — falling back to a proposal")
      propose(conversationId, userMessage, provider).collect { send(it) }
      return@agentTurnFlow
    }

    log.d(TAG, "▶ GOAL run conv=$conversationId plan=$resolvedPlanId")
    val result: PlanRunResult = planRunner.run(
      PlanRunRequest(
        conversationId = conversationId,
        planId = resolvedPlanId,
        background = false,
        budget = budget,
        executionMode = executionMode,
      ),
      provider = provider,
    )
    send(AgentEvent.StateChanged(AgentState.FINISHED))
    log.d(TAG, "✓ GOAL run done outcome=${result.outcome} steps=${result.executedStepIds}")

    if (result.outcome == PlanRunOutcome.FAILED) {
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(result.blockedReason ?: "Выполнение шага плана завершилось ошибкой."))
    } else {
      send(AgentEvent.PlanProgressed(result))
    }
  }

  /** The conversation's most-recently-touched NON-terminal plan, if any — same "which plan is this conversation's" policy [PlanRunner.run] itself falls back to for a null `planId`, duplicated here (not delegated) so [executeGoal] can tell "no plan at all" apart from every other status BEFORE ever calling [planRunner], and choose [propose] instead of letting [PlanRunner] throw. */
  private suspend fun resolveExistingPlanId(conversationId: String): String? =
    repository.observeByConversation(conversationId).first()
      .filter { it.plan.status !in TERMINAL_PLAN_STATUSES }
      .maxByOrNull { it.plan.updatedAt }
      ?.plan?.id

  private data class DraftTarget(val planId: String, val horizon: PlanHorizon, val existingVersion: PlanVersion?)

  /**
   * Which plan a PLAN turn in [conversationId] is about: the conversation's existing non-terminal
   * plan (a revision — [PlanPlanner.propose] gets its `existingVersion`), or a brand-new plan id/
   * horizon when none exists yet. [PlanTemplate.supportedHorizons][com.aiopenpocket.agent.core.plan.template.PlanTemplate.supportedHorizons]'s
   * first entry seeds a brand-new plan's horizon when [agentId] has a template; [PlanHorizon.SINGLE_RUN]
   * otherwise — [PlanPlanner.propose]'s own kdoc flags horizon selection as an open question for this
   * task to settle, and there is no chat UI yet to let the user choose explicitly (Task 12).
   */
  private suspend fun resolveDraftTarget(conversationId: String, agentId: String?): DraftTarget {
    val existing = repository.observeByConversation(conversationId).first()
      .filter { it.plan.status !in TERMINAL_PLAN_STATUSES }
      .maxByOrNull { it.plan.updatedAt }
    if (existing != null) {
      return DraftTarget(planId = existing.plan.id, horizon = existing.plan.horizon, existingVersion = existing.version)
    }
    val template = templateRegistry.getForAgent(agentId)
    val horizon = template?.supportedHorizons?.firstOrNull() ?: PlanHorizon.SINGLE_RUN
    return DraftTarget(planId = UUID.randomUUID().toString(), horizon = horizon, existingVersion = null)
  }

  /**
   * Fires [HookEvent.ON_PLAN_CREATED] for [plan]'s agent — closes Task 9's gap (`AgentHook.kt`'s own
   * kdoc already documents this event; nothing ever called it before this class, since Task 9 had no
   * real caller of [PlanRepository.saveProposal] for a brand-new plan yet). Mirrors
   * `com.aiopenpocket.agent.core.plan.execution.PlanCommands.firePlanApproved`'s exact shape: LOG-only
   * per the action matrix, so this is purely an audit-journal side effect — [propose] never branches
   * on its result. No-op for an agent-less plan (`plan.agentId == null`) or an agent with no hooks
   * configured (`firePlanHook`'s own short-circuit).
   */
  private suspend fun firePlanCreated(plan: AgentPlan, version: PlanVersion, agentId: String?) {
    if (agentId == null) return
    firePlanHook(
      hookRepository = hookRepository,
      hooks = hookRepository.getForAgent(agentId),
      event = HookEvent.ON_PLAN_CREATED,
      context = PlanHookContext(planId = plan.id, planVersion = version.version, planStatus = PlanStatus.AWAITING_APPROVAL),
      conversationId = plan.conversationId,
      runId = UUID.randomUUID().toString(),
    )
  }

  companion object {
    private const val TAG = "StructuredPlan"
    private val TERMINAL_PLAN_STATUSES = setOf(PlanStatus.COMPLETED, PlanStatus.CANCELLED, PlanStatus.FAILED)
  }
}
