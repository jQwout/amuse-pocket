package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What one [VerifierType] check concluded about one [PlanCriterion] (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 11) — the pass/fail half of `PlanVerifier`'s
 * job, kept separate from evidence PERSISTENCE: building/[com.aiopenpocket.agent.core.plan.persistence
 * .PlanRepository.addEvidence]-ing a real [com.aiopenpocket.agent.core.plan.model.PlanEvidence] row is
 * `PlanVerifier`'s job, not this file's. Never thrown — same "value, not exception" convention as
 * [com.aiopenpocket.agent.core.plan.validation.PlanTransitionResult]/
 * [com.aiopenpocket.agent.core.policy.ExecutionPolicy.evaluate]/`AgentValidator.validate`.
 *
 * [Satisfied]/[Unsatisfied] are the two outcomes a check that actually RAN can reach (mirrors the
 * plan's own Task 8 instructions: "Each verifier returns references to persisted source records/tool
 * trace IDs, not model-generated prose" — [Satisfied.reference]/[Satisfied.summary] always point at
 * something real that already happened, never invented). [InvalidConfig] is reserved for a
 * [PlanCriterion.verifier] whose [com.aiopenpocket.agent.core.plan.model.CriterionVerifier.config]
 * does not even identify what to check — the same "checkable" reading
 * [com.aiopenpocket.agent.core.plan.validation.PlanValidator.criterionIssues] already applies at
 * plan-creation time (`criterion_verifier_not_checkable`) — a PLAN AUTHORING problem, not a transient
 * one; `PlanVerifier` maps it to a needs-revision outcome, never a retry. [VerificationFailed] is
 * NEVER produced by [check] itself (a deterministic check either runs and concludes, or is refused via
 * [check]'s own `IllegalArgumentException` for [VerifierType.MODEL_ASSESSMENT]) — it exists on this
 * sealed interface only because `PlanVerifier`'s own MODEL_ASSESSMENT path (an LLM call/parse that can
 * genuinely fail as infrastructure, not as "criterion not met") needs a fourth, distinct case to return
 * without abusing [Unsatisfied] for a fundamentally different kind of failure.
 */
sealed interface CriterionVerdict {
  val criterion: PlanCriterion

  /** [reference] is a stable pointer a later audit can re-resolve (a tool-trace id, a memory fact id) — never free text. [summary] is human-readable, not itself proof of anything (mirrors [com.aiopenpocket.agent.core.plan.model.PlanEvidence]'s own kdoc). */
  data class Satisfied(override val criterion: PlanCriterion, val reference: String, val summary: String) : CriterionVerdict

  /** The check ran cleanly but found no proof yet — e.g. no matching tool call, or a matching call that itself errored. Retryable in spirit; `PlanVerifier` decides the actual [com.aiopenpocket.agent.core.plan.model.StepStatus]. */
  data class Unsatisfied(override val criterion: PlanCriterion, val reason: String) : CriterionVerdict

  /** [PlanCriterion.verifier.config] is missing what it needs to identify a concrete fact to check — a plan-authoring defect, not something retrying the same step fixes. */
  data class InvalidConfig(override val criterion: PlanCriterion, val reason: String) : CriterionVerdict

  /** MODEL_ASSESSMENT-only (see class kdoc): the verification MACHINERY itself failed (LLM call/parse), distinct from the model cleanly judging the criterion unmet (which is [Unsatisfied]). */
  data class VerificationFailed(override val criterion: PlanCriterion, val reason: String) : CriterionVerdict
}

/**
 * Inputs [DeterministicVerifierRegistry.check] needs beyond [PlanCriterion] itself, pre-fetched by
 * `PlanVerifier` so this file can stay pure (no suspend function, no repository/DAO type anywhere in
 * its signatures — mirrors [com.aiopenpocket.agent.core.plan.validation.PlanStateMachine]'s own "no
 * I/O" contract). [matchingMemoryFacts] only needs to be populated when the criterion being checked is
 * [VerifierType.MEMORY_FACT] — `PlanVerifier` does not eagerly query memory for every criterion, only
 * the ones that need it.
 */
data class DeterministicVerificationContext(
  val toolTrace: List<ToolTrace>,
  val matchingMemoryFacts: List<Fact> = emptyList(),
)

/**
 * Pure, total dispatch over the THREE checkable-by-code [VerifierType]s (design spec section 11:
 * "Детерминированные проверки имеют приоритет" — deterministic checks take priority over the LLM
 * verifier). [VerifierType.MODEL_ASSESSMENT] is deliberately given NO code path here at all (see
 * [check]'s kdoc): the plan's own Task 8 instructions single out "Never allow it to prove a
 * deterministic criterion", and refusing to even recognize that type in this registry makes that a
 * structural guarantee rather than a convention `PlanVerifier` could accidentally violate by routing a
 * criterion to the wrong place.
 *
 * Registry keys (Task 8 plan Step 2): [VerifierType.TOOL_RESULT], [VerifierType.MEMORY_FACT],
 * [VerifierType.USER_CONFIRMATION].
 */
object DeterministicVerifierRegistry {

  /** @throws IllegalArgumentException if [criterion]'s verifier type is [VerifierType.MODEL_ASSESSMENT] — see class kdoc; `PlanVerifier` must never call this for that type. */
  fun check(criterion: PlanCriterion, context: DeterministicVerificationContext): CriterionVerdict =
    when (criterion.verifier.type) {
      VerifierType.TOOL_RESULT -> checkToolResult(criterion, context.toolTrace)
      VerifierType.MEMORY_FACT -> checkMemoryFact(criterion, context.matchingMemoryFacts)
      VerifierType.USER_CONFIRMATION -> checkUserConfirmation(criterion)
      VerifierType.MODEL_ASSESSMENT -> throw IllegalArgumentException(
        "DeterministicVerifierRegistry never handles MODEL_ASSESSMENT (criterion '${criterion.id}'); " +
          "PlanVerifier must route it to the LLM verifier directly, never through this registry.",
      )
    }

  /**
   * Satisfied only by a [ToolResult.Success] call to the EXACT configured tool name — a
   * [ToolResult.Error] call to that same tool does NOT prove the criterion (design spec section 11:
   * "tool завершился успешно"; Task 8 required test "failed tool does not prove criterion"). Uses the
   * LAST matching call when a step called the same tool more than once — the model's final attempt is
   * what the step actually ended with.
   */
  private fun checkToolResult(criterion: PlanCriterion, toolTrace: List<ToolTrace>): CriterionVerdict {
    val toolName = criterion.verifier.config.rawString(CONFIG_TOOL_NAME)
    if (toolName.isNullOrBlank()) {
      return CriterionVerdict.InvalidConfig(
        criterion,
        "TOOL_RESULT verifier config for criterion '${criterion.id}' is missing a non-blank \"$CONFIG_TOOL_NAME\".",
      )
    }
    val call = toolTrace.lastOrNull { it.toolName == toolName }
      ?: return CriterionVerdict.Unsatisfied(criterion, "No call to tool '$toolName' appears in this step's tool trace.")
    return when (val result = call.result) {
      is ToolResult.Success -> CriterionVerdict.Satisfied(
        criterion,
        reference = "tool_trace:${call.toolName}#${call.iteration}@${call.timestamp}",
        summary = tr(
          "Инструмент «$toolName» выполнен успешно: ${result.data.take(SUMMARY_MAX_CHARS)}",
          "Tool “$toolName” ran successfully: ${result.data.take(SUMMARY_MAX_CHARS)}",
        ),
      )
      is ToolResult.Error -> CriterionVerdict.Unsatisfied(criterion, "Tool '$toolName' returned an error: ${result.message}")
    }
  }

  /**
   * Satisfied only by an ALREADY-PERSISTED [Fact] whose [Fact.key] exactly equals the configured key
   * (Task 8 required test "memory fact reference must exist") — a mere tool call is not enough, unlike
   * [checkToolResult]. [matchingFacts] is expected to already be narrowed to that key's prefix by
   * `PlanVerifier` (see [DeterministicVerificationContext] kdoc); this function still re-checks for an
   * EXACT key match since a prefix search can over-match (e.g. "budget.limit" vs "budget.limit.food").
   */
  private fun checkMemoryFact(criterion: PlanCriterion, matchingFacts: List<Fact>): CriterionVerdict {
    val key = criterion.verifier.config.rawString(CONFIG_MEMORY_KEY)
    if (key.isNullOrBlank()) {
      return CriterionVerdict.InvalidConfig(
        criterion,
        "MEMORY_FACT verifier config for criterion '${criterion.id}' is missing a non-blank \"$CONFIG_MEMORY_KEY\".",
      )
    }
    val fact = matchingFacts.firstOrNull { it.key == key }
      ?: return CriterionVerdict.Unsatisfied(criterion, "No memory fact with key '$key' exists yet.")
    return CriterionVerdict.Satisfied(
      criterion,
      reference = "memory_fact:${fact.id}",
      summary = tr(
        "Факт памяти «$key» = ${fact.value.take(SUMMARY_MAX_CHARS)}",
        "Memory fact “$key” = ${fact.value.take(SUMMARY_MAX_CHARS)}",
      ),
    )
  }

  /**
   * NEVER satisfied here — a real user confirmation is, by construction, evidence `PlanVerifier`
   * already excludes from the "still needs checking" set before this is even called (only a criterion
   * with NO existing [com.aiopenpocket.agent.core.plan.model.PlanEvidence] reaches [check] at all).
   * This function exists purely to produce a clear, typed reason (Task 8 required test "user
   * confirmation required when verifier says USER_CONFIRMATION") instead of silently falling through
   * to some other check — automated verification can never manufacture a human's own confirmation.
   */
  private fun checkUserConfirmation(criterion: PlanCriterion): CriterionVerdict {
    if (criterion.verifier.config.isEmpty()) {
      return CriterionVerdict.InvalidConfig(
        criterion,
        "USER_CONFIRMATION verifier config for criterion '${criterion.id}' is empty; nothing identifies what the user must confirm.",
      )
    }
    return CriterionVerdict.Unsatisfied(
      criterion,
      "Criterion '${criterion.id}' requires the user's own confirmation; automated verification cannot supply that.",
    )
  }

  private const val CONFIG_TOOL_NAME = "toolName"
  private const val CONFIG_MEMORY_KEY = "key"
  private const val SUMMARY_MAX_CHARS = 200
}

/**
 * Reads a string-typed key out of a [PlanCriterion.verifier]
 * [com.aiopenpocket.agent.core.plan.model.CriterionVerifier.config] payload — same pattern as
 * [com.aiopenpocket.agent.core.plan.planner.PlanPlanner]'s own private `rawString` (that one is
 * file-private, so it is not reused directly), kept `internal` (not `private`) so `PlanVerifier` in
 * this same package can read a criterion's config (e.g. which memory key to pre-fetch) without a
 * second, drifting copy of this one-liner.
 */
internal fun JsonObject.rawString(key: String): String? = (this[key] as? JsonPrimitive)?.content
