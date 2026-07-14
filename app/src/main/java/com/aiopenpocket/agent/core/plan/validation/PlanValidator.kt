package com.aiopenpocket.agent.core.plan.validation

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.VerifierType

/**
 * How serious a [PlanValidationIssue] is. [ERROR] means the candidate [PlanVersion] must not be
 * accepted by `submit_plan`/`propose_plan_revision` (design spec section 7); [WARNING] flags
 * something the spec explicitly *permits* but calls out as weak (e.g. a required criterion backed
 * only by [VerifierType.MODEL_ASSESSMENT] — design spec section 5: "помечается в UI как слабое
 * доказательство") — worth surfacing to a user/builder UI, but not itself a reason to reject the
 * version. [PlanValidator.isValid] is the single place that turns a `List<PlanValidationIssue>`
 * into a yes/no answer, so callers never have to re-derive "does WARNING block?" themselves.
 */
enum class PlanValidationSeverity { ERROR, WARNING }

/**
 * One rule violation found by [PlanValidator.validate]. [code] is a stable, machine-checkable
 * identifier (assert on it in tests, branch a builder UI on it) — mirrors
 * [com.aiopenpocket.agent.core.agent.ValidationIssue] one-for-one in spirit, this package just
 * can't reuse that type directly since it is defined in terms of an [AgentDefinition], not a
 * [PlanVersion]. [path] optionally names the offending step/criterion/dependency/constraint (a
 * step id, a `"stepId -> missingDepId"` edge, a cycle rendered as `"a -> b -> a"`, a criterion id,
 * or a constraint string) so a builder UI can point at the exact offending node instead of only
 * showing a flat list.
 */
data class PlanValidationIssue(
  val code: String,
  val message: String,
  val path: String? = null,
  val severity: PlanValidationSeverity = PlanValidationSeverity.ERROR,
)

/**
 * Pure, stateless structural/scope validator for a candidate [PlanVersion] — the code-level gate
 * behind `submit_plan`/`propose_plan_revision` (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 7; plan's Global Constraints). No I/O, no
 * Android, no coroutines, no randomness — [validate] never throws and never short-circuits on the
 * first problem, exactly like [com.aiopenpocket.agent.core.agent.AgentValidator.validate] and
 * [PlanStateMachine]: it collects every violation into the returned list so a builder UI can show
 * them all in one pass instead of one round-trip per fix.
 *
 * ## Signature deviation from `task-2-brief.md`'s literal `validate(version, agent,
 * requiredConstraints)`
 *
 * This object's [validate] also takes an explicit [PlanHorizon]. That is a deliberate, necessary
 * addition: [PlanVersion] itself carries no horizon field at all (only [com.aiopenpocket.agent
 * .core.plan.model.AgentPlan.horizon] does — see that type), yet the brief's own required test
 * list includes "PROGRAM periodic interval below 15 minutes" and "PROGRAM scheduled step when
 * agent lacks the heartbeat capability", and this task's own instructions explicitly ask for
 * "PROGRAM vs SINGLE_RUN structural rules". None of that is checkable from `(version, agent,
 * requiredConstraints)` alone. Rather than smuggle the whole
 * [com.aiopenpocket.agent.core.plan.model.AgentPlan] aggregate in (which would drag in unrelated
 * mutable runtime fields like `status`/`activeStepId` that this static check has no business
 * reading), the minimal, precisely-scoped addition is the one field this function actually needs.
 *
 * ## What "budgets sane" means here
 *
 * Neither [PlanVersion] nor [PlanStep] carries any budget-shaped field at all — the design's "max
 * LLM iterations per step / max tool calls per run / max retries per step / deadline" budget
 * (section 10) is a `PlanRunBudget`, a **runtime** concept `PlanStateMachine`'s own kdoc already
 * attributes to Task 7, never a field of the immutable, approvable plan *content* this validator
 * checks. The only budget-shaped things that actually exist on [PlanVersion] today are the step
 * and criteria **counts** — so [MAX_STEPS]/[MAX_CRITERIA] (design section 7's "default limits по
 * числу steps/criteria") are what "budgets sane" cashes out to at this layer.
 *
 * ## What "no unreachable/orphan steps" means here
 *
 * A [PlanStep] graph has no single required root — independent step chains (e.g. a budget plan's
 * "collect categories" and "collect monthly limit" steps, neither depending on the other) are
 * completely legal; every step with satisfied dependencies (including the empty set) becomes
 * runnable on its own. There is therefore no extra "orphan" concept beyond "a [PlanStep.dependsOn]
 * entry that does not resolve to a real step in this same version" — already covered by the
 * dangling-dependency check below. This validator does not invent a second, speculative
 * reachability rule beyond that.
 *
 * ## `agent.toolNames ∩ step.expectedTools ∩ ExecutionPolicy`
 *
 * The plan's Global Constraints define a step's real runtime tool scope as that three-way
 * intersection. Only the first two terms are static, plan-authoring-time facts; the third
 * ([com.aiopenpocket.agent.core.policy.ExecutionPolicy.evaluate]) is evaluated **per tool call**,
 * fed by a [com.aiopenpocket.agent.core.hook.HookDecision]/[com.aiopenpocket.agent.core.policy
 * .ExecutionMode]/[com.aiopenpocket.agent.core.mcp.RiskClass] that simply do not exist yet at
 * validation time. [validate] therefore only checks `step.expectedTools ⊆ agent.toolNames` (brief
 * Step 5) — the runtime intersection with policy is necessarily re-checked later, on every actual
 * call, by the executor.
 */
object PlanValidator {

  /** Design spec section 7 "default limits по числу steps" — brief: "more than 30 steps" is rejected, so 30 itself is still legal. */
  const val MAX_STEPS = 30

  /** Design spec section 7 "default limits по числу criteria" — brief: "more than 20 criteria" is rejected, so 20 itself is still legal. */
  const val MAX_CRITERIA = 20

  /** Brief Step 5: "PROGRAM periodic interval below 15 minutes" is rejected, so 15 itself is still legal. */
  const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

  /**
   * `submit_plan`/`request_plan_information`/`propose_plan_revision` (design spec section 7) — the
   * planner's own control schemas. These never name a real device/app capability, so they can
   * never legally appear in a [PlanStep.expectedTools] regardless of what an agent's
   * [AgentDefinition.toolNames] happens to contain (brief Step 5: "planner control schemas are not
   * legal step tools").
   */
  val PLANNER_CONTROL_TOOL_NAMES: Set<String> = setOf("submit_plan", "request_plan_information", "propose_plan_revision")

  /** The one capability name (see [AgentDefinition.toolNames], `AgentValidator.KNOWN_TOOL_NAMES`) that gates any scheduled step on a [PlanHorizon.PROGRAM] plan (brief Step 5). */
  const val HEARTBEAT_TOOL_NAME = "heartbeat"

  /**
   * Every structural/scope problem found in [version], given the [horizon] its owning
   * [com.aiopenpocket.agent.core.plan.model.AgentPlan] runs under, the [agent] it is bound to, and
   * the domain template's [requiredConstraints] (Task 4's `PlanTemplate` supplies this set — an
   * explicit argument, never an implicit default, so a caller can never silently skip the
   * "medical/financial safety constraints не удалены" check by forgetting to pass it). Empty
   * result does not necessarily mean "approve this" on its own — see [isValid], which is the
   * `severity`-aware yes/no a caller should actually branch on.
   */
  fun validate(
    version: PlanVersion,
    horizon: PlanHorizon,
    agent: AgentDefinition,
    requiredConstraints: Set<String>,
  ): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()

    if (version.objective.isBlank()) {
      issues += PlanValidationIssue("blank_objective", "Objective must not be blank.")
    }

    issues += duplicateIdIssues(version)
    issues += danglingDependencyIssues(version.steps)
    issues += cycleIssue(version.steps)
    issues += toolScopeIssues(version.steps, agent)
    issues += sizeLimitIssues(version)
    issues += scheduleIssues(version.steps, horizon, agent)
    issues += wellFormedStepIssues(version.steps)
    issues += criterionIssues(version.criteria)
    issues += requiredConstraintIssues(version, requiredConstraints)

    return issues
  }

  /** `true` iff nothing in [issues] is [PlanValidationSeverity.ERROR] — the actual "can this be approved" gate; a [PlanValidationSeverity.WARNING]-only list is still valid. */
  fun isValid(issues: List<PlanValidationIssue>): Boolean = issues.none { it.severity == PlanValidationSeverity.ERROR }

  // --- individual rules, one function per concern (kept independently testable/readable) --------

  private fun duplicateIdIssues(version: PlanVersion): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    val dupSteps = duplicatesOf(version.steps.map { it.id })
    if (dupSteps.isNotEmpty()) {
      issues += PlanValidationIssue(
        code = "duplicate_step_id",
        message = "Duplicate step id(s): ${dupSteps.joinToString()}",
        path = dupSteps.joinToString(),
      )
    }
    val dupCriteria = duplicatesOf(version.criteria.map { it.id })
    if (dupCriteria.isNotEmpty()) {
      issues += PlanValidationIssue(
        code = "duplicate_criterion_id",
        message = "Duplicate criterion id(s): ${dupCriteria.joinToString()}",
        path = dupCriteria.joinToString(),
      )
    }
    return issues
  }

  private fun duplicatesOf(ids: List<String>): List<String> =
    ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

  /** A [PlanStep.dependsOn] entry that does not resolve to any [PlanStep.id] in the same [steps] list. */
  private fun danglingDependencyIssues(steps: List<PlanStep>): List<PlanValidationIssue> {
    val knownIds = steps.map { it.id }.toSet()
    return steps
      .sortedBy { it.id }
      .flatMap { step -> step.dependsOn.filter { it !in knownIds }.sorted().map { missing -> step.id to missing } }
      .map { (stepId, missing) ->
        PlanValidationIssue(
          code = "dangling_dependency",
          message = "Step '$stepId' depends on unknown step '$missing'.",
          path = "$stepId -> $missing",
        )
      }
  }

  /**
   * Deterministic DFS with an explicit `visiting`/`visited` split (brief Step 4). [PlanStep
   * .dependsOn] is a `Set`, so every traversal sorts it first — otherwise which cycle (if several
   * exist) gets reported, and in what order, would depend on `HashSet` iteration order rather than
   * plan content. Dangling references (a dependency with no matching step) are treated as "no
   * further edge" here, not as a cycle — [danglingDependencyIssues] reports those separately.
   */
  private fun cycleIssue(steps: List<PlanStep>): List<PlanValidationIssue> {
    val byId = steps.associateBy { it.id }
    val visiting = linkedSetOf<String>()
    val visited = mutableSetOf<String>()

    fun dfs(id: String): List<String>? {
      if (id in visiting) return visiting.dropWhile { it != id } + id
      if (id in visited) return null
      val step = byId[id] ?: return null
      visiting += id
      for (dep in step.dependsOn.sorted()) {
        dfs(dep)?.let { return it }
      }
      visiting -= id
      visited += id
      return null
    }

    for (id in steps.map { it.id }) {
      if (id !in visited) {
        dfs(id)?.let { cycle ->
          return listOf(
            PlanValidationIssue(
              code = "dependency_cycle",
              message = "Step dependency cycle detected: ${cycle.joinToString(" -> ")}",
              path = cycle.joinToString(" -> "),
            ),
          )
        }
      }
    }
    return emptyList()
  }

  /** `expectedTools` must be a subset of `agent.toolNames`; a planner control tool name is never legal regardless of agent scope (brief Step 5). */
  private fun toolScopeIssues(steps: List<PlanStep>, agent: AgentDefinition): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    for (step in steps.sortedBy { it.id }) {
      val controlToolsUsed = step.expectedTools intersect PLANNER_CONTROL_TOOL_NAMES
      for (tool in controlToolsUsed.sorted()) {
        issues += PlanValidationIssue(
          code = "planner_control_tool_in_step",
          message = "Step '${step.id}' declares planner control tool '$tool', which is never a legal step tool.",
          path = "${step.id}.expectedTools[$tool]",
        )
      }
      val outOfScope = step.expectedTools - agent.toolNames - controlToolsUsed
      for (tool in outOfScope.sorted()) {
        issues += PlanValidationIssue(
          code = "tool_outside_agent_scope",
          message = "Step '${step.id}' expects tool '$tool', which is outside agent '${agent.id}' toolNames.",
          path = "${step.id}.expectedTools[$tool]",
        )
      }
    }
    return issues
  }

  private fun sizeLimitIssues(version: PlanVersion): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    if (version.steps.size > MAX_STEPS) {
      issues += PlanValidationIssue("too_many_steps", "Plan has ${version.steps.size} steps, exceeding the limit of $MAX_STEPS.")
    }
    if (version.criteria.size > MAX_CRITERIA) {
      issues += PlanValidationIssue("too_many_criteria", "Plan has ${version.criteria.size} criteria, exceeding the limit of $MAX_CRITERIA.")
    }
    return issues
  }

  /**
   * [PlanHorizon.SINGLE_RUN] plans may not schedule any step at all ([PlanSchedule]'s own kdoc:
   * "PlanValidator (Task 2) rejects a scheduled step on a PlanHorizon.SINGLE_RUN plan"). On
   * [PlanHorizon.PROGRAM], a schedule must itself be well-formed for its [ScheduleKind] and every
   * [ScheduleKind.PERIODIC] interval must be at least [MIN_PERIODIC_INTERVAL_MINUTES]; if *any*
   * step carries a schedule at all, the agent must hold [HEARTBEAT_TOOL_NAME] (brief Step 5: the
   * user's selection of the heartbeat capability is their consent to proactive background
   * execution — `PlanScheduler` itself stays an internal service, never user-facing).
   */
  private fun scheduleIssues(steps: List<PlanStep>, horizon: PlanHorizon, agent: AgentDefinition): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    var anyScheduled = false
    for (step in steps.sortedBy { it.id }) {
      val schedule = step.schedule ?: continue
      anyScheduled = true
      if (horizon == PlanHorizon.SINGLE_RUN) {
        issues += PlanValidationIssue(
          code = "schedule_on_single_run_plan",
          message = "Step '${step.id}' has a schedule, but SINGLE_RUN plans cannot schedule steps.",
          path = step.id,
        )
        continue
      }
      when (schedule.kind) {
        ScheduleKind.ONE_TIME -> {
          val delay = schedule.delayMinutes
          if (delay == null || delay <= 0) {
            issues += PlanValidationIssue(
              code = "invalid_schedule_delay",
              message = "Step '${step.id}' has a ONE_TIME schedule with a missing/non-positive delayMinutes.",
              path = step.id,
            )
          }
        }
        ScheduleKind.PERIODIC -> {
          val interval = schedule.intervalMinutes
          if (interval == null) {
            issues += PlanValidationIssue(
              code = "invalid_schedule_interval",
              message = "Step '${step.id}' has a PERIODIC schedule with no intervalMinutes.",
              path = step.id,
            )
          } else if (interval < MIN_PERIODIC_INTERVAL_MINUTES) {
            issues += PlanValidationIssue(
              code = "periodic_interval_too_short",
              message = "Step '${step.id}' PERIODIC interval is ${interval}min, below the ${MIN_PERIODIC_INTERVAL_MINUTES}min minimum.",
              path = step.id,
            )
          }
        }
      }
    }
    if (horizon == PlanHorizon.PROGRAM && anyScheduled && HEARTBEAT_TOOL_NAME !in agent.toolNames) {
      issues += PlanValidationIssue(
        code = "missing_heartbeat_capability",
        message = "Plan has scheduled steps but agent '${agent.id}' lacks the '$HEARTBEAT_TOOL_NAME' capability.",
      )
    }
    return issues
  }

  private fun wellFormedStepIssues(steps: List<PlanStep>): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    for (step in steps.sortedBy { it.id }) {
      if (step.id.isBlank()) issues += PlanValidationIssue("blank_step_id", "A step has a blank id.")
      if (step.title.isBlank()) issues += PlanValidationIssue("blank_step_title", "Step '${step.id}' has a blank title.", path = step.id)
      if (step.instruction.isBlank()) {
        issues += PlanValidationIssue("blank_step_instruction", "Step '${step.id}' has a blank instruction.", path = step.id)
      }
    }
    return issues
  }

  /**
   * A criterion must be "checkable" (this task's own instructions: "has a verifier/evidence source
   * per the spec") to ever gate [com.aiopenpocket.agent.core.plan.model.PlanStatus.COMPLETED]
   * meaningfully. [CriterionVerifier.type] itself is always present (it is a non-null enum field),
   * so the only way a verifier can fail to identify anything concrete to check is an empty
   * [CriterionVerifier.config] on a type that is supposed to point at a real fact —
   * [VerifierType.TOOL_RESULT]/[VerifierType.MEMORY_FACT]/[VerifierType.USER_CONFIRMATION]. Design
   * spec section 5 singles out [VerifierType.MODEL_ASSESSMENT] as the one deliberately free-form,
   * configless, "weak evidence" kind — never itself a reason to reject, only (for a *required*
   * criterion) a [PlanValidationSeverity.WARNING].
   *
   * UNCERTAIN: the spec does not give a formal schema for [CriterionVerifier.config], so "empty
   * config on a non-MODEL_ASSESSMENT type" is this task's own operational reading of "checkable",
   * not a literal rule quoted anywhere in the design doc. Flagged in the task report.
   */
  private fun criterionIssues(criteria: List<PlanCriterion>): List<PlanValidationIssue> {
    val issues = mutableListOf<PlanValidationIssue>()
    for (criterion in criteria.sortedBy { it.id }) {
      if (criterion.id.isBlank()) issues += PlanValidationIssue("blank_criterion_id", "A criterion has a blank id.")
      if (criterion.description.isBlank()) {
        issues += PlanValidationIssue("blank_criterion_description", "Criterion '${criterion.id}' has a blank description.", path = criterion.id)
      }
      if (!isCheckable(criterion.verifier)) {
        issues += PlanValidationIssue(
          code = "criterion_verifier_not_checkable",
          message = "Criterion '${criterion.id}' verifier (${criterion.verifier.type}) has no config identifying what to check.",
          path = criterion.id,
          severity = if (criterion.required) PlanValidationSeverity.ERROR else PlanValidationSeverity.WARNING,
        )
      } else if (criterion.required && criterion.verifier.type == VerifierType.MODEL_ASSESSMENT) {
        issues += PlanValidationIssue(
          code = "required_criterion_weak_evidence",
          message = "Criterion '${criterion.id}' is required but backed only by MODEL_ASSESSMENT, the spec's weakest evidence kind.",
          path = criterion.id,
          severity = PlanValidationSeverity.WARNING,
        )
      }
    }
    return issues
  }

  private fun isCheckable(verifier: CriterionVerifier): Boolean =
    verifier.type == VerifierType.MODEL_ASSESSMENT || verifier.config.isNotEmpty()

  /** Design spec section 7: "medical/financial safety constraints не удалены domain policy" — every entry [requiredConstraints] (Task 4's `PlanTemplate`) names must still be literally present in [PlanVersion.constraints]. */
  private fun requiredConstraintIssues(version: PlanVersion, requiredConstraints: Set<String>): List<PlanValidationIssue> {
    val present = version.constraints.toSet()
    return (requiredConstraints - present).sorted().map { constraint ->
      PlanValidationIssue(
        code = "required_constraint_removed",
        message = "Required domain safety constraint missing from plan: '$constraint'.",
        path = constraint,
      )
    }
  }
}
