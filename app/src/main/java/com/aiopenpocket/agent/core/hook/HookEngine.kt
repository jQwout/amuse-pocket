package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.StepStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * What [HookEngine.evaluate] decided for one [HookEvent] firing, after folding together every
 * [AgentHook] that matched. Deliberately flat (no per-hook detail) — this is the shape
 * [com.aiopenpocket.agent.loop.ReactLoopRunner] actually *applies* to the loop; per-hook detail for the
 * `agent_hook_runs` journal comes from [HookEngine.matched] separately, so this contract can stay
 * frozen even as the journal's needs evolve.
 *
 * [denied] wins over an [HookActionType.ALLOW] unconditionally (see [HookEngine.toDecision]) —
 * there is no "most specific wins" tie-break, a single matching `DENY` anywhere always denies.
 */
data class HookDecision(
  val addedInstructions: List<String> = emptyList(),
  val denied: Boolean = false,
  val approvalRequired: Boolean = false,
  val stopped: Boolean = false,
  val logs: List<String> = emptyList(),
)

/**
 * Everything an [AgentHook.conditionType] can match against for one lifecycle firing. Fields are
 * nullable/defaulted because most only apply to a subset of [HookEvent]s — e.g. [toolName] is only
 * ever populated for [HookEvent.BEFORE_TOOL]/[HookEvent.AFTER_TOOL], so a condition that inspects it
 * simply never matches a turn-level event, which is exactly why that scoping doesn't need its own
 * enforcement beyond the action/event matrix.
 *
 * Deliberately does NOT carry the live `messages`/`ChatMessage` list or raw tool arguments/results:
 * hooks are declarative allow/deny/log rules over a small, stable surface, not a place to grow
 * arbitrary condition logic over full turn state (that would start to smell like the "no user
 * JS/Kotlin" constraint this whole engine exists to avoid).
 *
 * [planId]/[planVersion]/[stepId]/[planStatus]/[stepStatus]/[evidenceIds]/[background] (Task 9 of
 * the Structured Plan Engine) are the plan-lifecycle counterpart of [iteration]/[toolName]: only
 * populated for [HookEvent.ON_PLAN_CREATED]/[HookEvent.ON_PLAN_APPROVED]/[HookEvent.BEFORE_PLAN_STEP]/
 * [HookEvent.AFTER_PLAN_STEP]/[HookEvent.ON_PLAN_BLOCKED]/[HookEvent.ON_PLAN_COMPLETED] firings, always
 * default/`null` for the original turn-level events. All seven are defaulted so every existing
 * turn-hook call site ([com.aiopenpocket.agent.loop.ReactLoopRunner]'s `HookContext(iteration = ...)`
 * constructions) compiles and behaves exactly as before — this is the SAME [HookContext]
 * [HookEngine.matched] always accepted, extended in place rather than taught a second context type
 * (`com.aiopenpocket.agent.core.plan.execution.PlanHookContext` is a small typed builder for THIS
 * shape, not a competing one — see its own kdoc). No condition type reads these yet (mirrors
 * [toolSucceeded]/[errorMessage] having no dedicated reader either, until [HookEvent.ON_ERROR]/
 * `AFTER_TOOL`-specific conditions are added) — they exist so a future condition type can.
 */
data class HookContext(
  val iteration: Int = 0,
  val toolName: String? = null,
  val toolSucceeded: Boolean? = null,
  val errorMessage: String? = null,
  val planId: String? = null,
  val planVersion: Int? = null,
  val stepId: String? = null,
  val planStatus: PlanStatus? = null,
  val stepStatus: StepStatus? = null,
  val evidenceIds: List<String> = emptyList(),
  val background: Boolean = false,
)

/**
 * Declarative, deterministic lifecycle engine for [AgentHook]s (design spec section 10). Pure: no
 * I/O, no suspend, no calls to an LLM or a tool, and — critically — no call back into itself. Given
 * the same inputs it always returns the same [HookDecision]; every side effect (running a tool,
 * calling the model, writing the `agent_hook_runs` journal) is the CALLER's job
 * ([com.aiopenpocket.agent.loop.ReactLoopRunner]).
 *
 * Rules enforced here (each pinned by a [HookEngineTest] case):
 * - only hooks declared for the [HookEvent] being evaluated are even considered;
 * - a disabled hook, or one whose [HookActionType] isn't valid for its own event (the matrix in
 *   [actionsAllowedFor] — publish time already rejects this via `AgentValidator`, this is
 *   defense-in-depth against a future caller that bypasses that), never fires;
 * - candidates are sorted by `(priority, id)` ascending before applying, so authoring order never
 *   matters and results are reproducible;
 * - a matching [HookActionType.DENY] always wins over [HookActionType.ALLOW], regardless of order;
 * - [executedSoFar]/[maxExecutionsPerRun] cap how many hooks can fire across a WHOLE run (many
 *   [evaluate] calls share one running total, threaded through by the caller) — once spent, further
 *   matching LOG/ADD_INSTRUCTION/ALLOW hooks are skipped rather than applied. The guard actions
 *   ([BUDGET_EXEMPT_ACTIONS]: DENY/REQUIRE_APPROVAL/STOP) are exempt and still fire: the cap exists to
 *   bound journal writes and prompt growth, and it must never fail OPEN — a long run must not quietly
 *   outlive the very rule that forbids a tool;
 * - a hook whose condition/action JSON fails to parse fails CLOSED (treated as "doesn't match" or
 *   "fired with no payload", see [matched]) instead of throwing — this is what makes `ON_ERROR`
 *   non-recursive: there is no code path here that reacts to a processing failure by evaluating
 *   [HookEvent.ON_ERROR] again, so a broken `ON_ERROR` hook cannot trigger a second `ON_ERROR` pass.
 */
object HookEngine {

  /** Upper bound on how many hooks may actually fire across one [com.aiopenpocket.agent.loop.ReactLoopRunner.run] call (guard actions excepted, see [BUDGET_EXEMPT_ACTIONS]). */
  const val MAX_EXECUTIONS_PER_RUN = 50

  /**
   * Guard actions that keep firing after [MAX_EXECUTIONS_PER_RUN] is spent. Each one only ever
   * narrows what the run may do (forbid a tool, ask a human, halt), so applying it past the cap can
   * cost a journal row at most — skipping it would silently widen what the agent is allowed to do.
   */
  val BUDGET_EXEMPT_ACTIONS: Set<HookActionType> =
    setOf(HookActionType.DENY, HookActionType.REQUIRE_APPROVAL, HookActionType.STOP)

  /**
   * One [AgentHook] that actually fired for a single [evaluate]/[matched] call — enabled, its
   * [AgentHook.event] matched, its [AgentHook.actionType] valid for that event, its condition
   * matched, and still within budget (or a [BUDGET_EXEMPT_ACTIONS] guard). [detail] is the parsed instruction/log text for
   * [HookActionType.ADD_INSTRUCTION]/[HookActionType.LOG] (empty for payload-less actions);
   * [error] is set instead when [AgentHook.actionJson] failed to parse (the hook still "fired" —
   * matched and consumed budget — just with no usable payload).
   */
  data class Firing(
    val hookId: String,
    val actionType: HookActionType,
    val detail: String = "",
    val error: String? = null,
  )

  /**
   * The event/action compatibility matrix from the design spec, reused verbatim by
   * `com.aiopenpocket.agent.core.agent.AgentValidator` so publish-time rejection can never drift
   * from what the runtime actually enforces. `com.aiopenpocket.ui.agents.builder.components.HookEditorDialog`
   * also derives its per-event action picker from this same function, for the same reason.
   *
   * The six plan-lifecycle events (Task 9; design spec section 12.2 Step 3) are deliberately
   * LOG-only except [HookEvent.BEFORE_PLAN_STEP] — a plan hook is declarative-only (never mutates
   * the approved plan, never adds evidence), so [HookEvent.ON_PLAN_CREATED]/[HookEvent.ON_PLAN_APPROVED]/
   * [HookEvent.AFTER_PLAN_STEP]/[HookEvent.ON_PLAN_BLOCKED]/[HookEvent.ON_PLAN_COMPLETED] can only ever
   * journal an audit entry, never deny/stop/approve/instruct after the fact. Only
   * [HookEvent.BEFORE_PLAN_STEP] — the one point where a decision can still change what happens next —
   * gets the richer [BEFORE_PLAN_STEP_ACTIONS] set (mirrors [HookEvent.BEFORE_TOOL]'s own reasoning,
   * but with [HookActionType.ADD_INSTRUCTION] instead of [HookActionType.ALLOW]: a plan step has no
   * "ALLOW" concept of its own since it isn't a single tool call, but CAN still receive an ephemeral
   * instruction the way [HookEvent.BEFORE_TURN] can).
   */
  fun actionsAllowedFor(event: HookEvent): Set<HookActionType> = when (event) {
    HookEvent.BEFORE_TURN -> BEFORE_TURN_ACTIONS
    HookEvent.AFTER_TURN -> LOG_AND_STOP
    HookEvent.BEFORE_TOOL -> BEFORE_TOOL_ACTIONS
    HookEvent.AFTER_TOOL -> LOG_AND_STOP
    HookEvent.ON_ERROR -> LOG_AND_STOP
    HookEvent.ON_COMPLETE -> LOG_ONLY
    HookEvent.ON_PLAN_CREATED -> LOG_ONLY
    HookEvent.ON_PLAN_APPROVED -> LOG_ONLY
    HookEvent.BEFORE_PLAN_STEP -> BEFORE_PLAN_STEP_ACTIONS
    HookEvent.AFTER_PLAN_STEP -> LOG_ONLY
    HookEvent.ON_PLAN_BLOCKED -> LOG_ONLY
    HookEvent.ON_PLAN_COMPLETED -> LOG_ONLY
  }

  /**
   * The ordered, budget-limited list of hooks that actually fire for this [event]/[context]. The
   * primary entry point for [com.aiopenpocket.agent.loop.ReactLoopRunner], which needs per-hook
   * [AgentHook.id] attribution to journal `agent_hook_runs` rows; [evaluate] is [toDecision] folded
   * over this same list, so the two can never disagree about which hooks fired.
   */
  fun matched(
    hooks: List<AgentHook>,
    event: HookEvent,
    context: HookContext,
    executedSoFar: Int = 0,
    maxExecutionsPerRun: Int = MAX_EXECUTIONS_PER_RUN,
  ): List<Firing> {
    val allowedActions = actionsAllowedFor(event)
    val candidates = hooks
      .asSequence()
      .filter { it.event == event && it.enabled && it.actionType in allowedActions }
      .sortedWith(compareBy({ it.priority }, { it.id }))
      .toList()

    val firings = mutableListOf<Firing>()
    var executed = executedSoFar
    for (candidate in candidates) {
      // Бюджет исчерпан — пропускаем (а не break) только «мягкие» действия: DENY/REQUIRE_APPROVAL/STOP
      // ниже по приоритету должны сработать и на 51-м срабатывании. Раньше здесь стоял break, и
      // после 50 срабатываний (их легко набрать LOG-хуком на каждый тул) запрет агента просто
      // переставал действовать — ограничитель расхода превращался в способ обойти правило.
      if (executed >= maxExecutionsPerRun && candidate.actionType !in BUDGET_EXEMPT_ACTIONS) continue
      val isMatch = runCatching { conditionMatches(candidate.conditionType, candidate.conditionJson, context) }
        .getOrDefault(false) // malformed conditionJson fails closed: never matches, never throws.
      if (!isMatch) continue

      var detail = ""
      var error: String? = null
      runCatching { extractDetail(candidate.actionType, candidate.actionJson) }
        .onSuccess { detail = it }
        .onFailure { error = it.message ?: "invalid action payload" } // malformed actionJson: hook still fires, empty payload.
      firings += Firing(candidate.id, candidate.actionType, detail, error)
      executed++
    }
    return firings
  }

  /** Folds [firings] (as returned by [matched]) into the single [HookDecision] the loop applies. */
  fun toDecision(firings: List<Firing>): HookDecision {
    val logs = firings.map { firing ->
      val suffix = when {
        firing.error != null -> " ERROR: ${firing.error}"
        firing.detail.isNotBlank() -> " ${firing.detail}"
        else -> ""
      }
      "${firing.hookId}:${firing.actionType.name}$suffix"
    }
    return HookDecision(
      addedInstructions = firings
        .filter { it.actionType == HookActionType.ADD_INSTRUCTION }
        .map { it.detail }
        .filter { it.isNotBlank() },
      denied = firings.any { it.actionType == HookActionType.DENY },
      approvalRequired = firings.any { it.actionType == HookActionType.REQUIRE_APPROVAL },
      stopped = firings.any { it.actionType == HookActionType.STOP },
      logs = logs,
    )
  }

  /** `evaluate(hooks, event, context) == toDecision(matched(hooks, event, context))` — see [matched] for why the split exists. */
  fun evaluate(
    hooks: List<AgentHook>,
    event: HookEvent,
    context: HookContext,
    executedSoFar: Int = 0,
    maxExecutionsPerRun: Int = MAX_EXECUTIONS_PER_RUN,
  ): HookDecision = toDecision(matched(hooks, event, context, executedSoFar, maxExecutionsPerRun))

  /**
   * Interprets [AgentHook.conditionType]/[conditionJson] against [context]. Unrecognized types fail
   * CLOSED (never match) rather than open — a typo'd or future/unsupported conditionType must never
   * silently behave like "always", which would turn a scoped rule into an unscoped one.
   */
  private fun conditionMatches(conditionType: String, conditionJson: String, context: HookContext): Boolean = when (conditionType) {
    "always" -> true
    "tool_name_in" -> context.toolName != null && context.toolName in Json.decodeFromString<ToolNameCondition>(conditionJson).names
    else -> false
  }

  /** Payload text for the two actions that carry one ({"text": "..."} for both ADD_INSTRUCTION and LOG); every other action is payload-less. */
  private fun extractDetail(actionType: HookActionType, actionJson: String): String = when (actionType) {
    HookActionType.ADD_INSTRUCTION, HookActionType.LOG -> Json.decodeFromString<TextPayload>(actionJson).text
    HookActionType.REQUIRE_APPROVAL, HookActionType.ALLOW, HookActionType.DENY, HookActionType.STOP -> ""
  }

  private val BEFORE_TURN_ACTIONS = setOf(HookActionType.ADD_INSTRUCTION, HookActionType.LOG, HookActionType.STOP)
  private val BEFORE_TOOL_ACTIONS =
    setOf(HookActionType.REQUIRE_APPROVAL, HookActionType.ALLOW, HookActionType.DENY, HookActionType.LOG, HookActionType.STOP)
  private val BEFORE_PLAN_STEP_ACTIONS = setOf(
    HookActionType.ADD_INSTRUCTION, HookActionType.REQUIRE_APPROVAL, HookActionType.DENY, HookActionType.LOG, HookActionType.STOP,
  )
  private val LOG_AND_STOP = setOf(HookActionType.LOG, HookActionType.STOP)
  private val LOG_ONLY = setOf(HookActionType.LOG)
}

/** `conditionJson` shape for `conditionType == "tool_name_in"`. */
@Serializable
private data class ToolNameCondition(val names: Set<String> = emptySet())

/** `actionJson` shape for `ADD_INSTRUCTION`/`LOG` — the only two actions that carry a payload. */
@Serializable
private data class TextPayload(val text: String = "")
