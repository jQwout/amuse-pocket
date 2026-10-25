package com.aiopenpocket.agent.core.plan.planner

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.template.PlanTemplate
import com.aiopenpocket.agent.core.plan.template.PlanTemplateRegistry
import com.aiopenpocket.agent.core.plan.validation.PlanStateMachine
import com.aiopenpocket.agent.core.plan.validation.PlanValidationIssue
import com.aiopenpocket.agent.core.plan.validation.PlanValidator
import com.aiopenpocket.agent.core.plan.validation.PlanVersionResult
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Terminal outcome of one [PlanPlanner.propose] call (Task 4 Step 1; design spec
 * `2026-07-12-structured-plan-engine-design.md` section 7). Exactly one of these is ever returned —
 * there is no "in progress"/streaming variant, since a planning turn is always exactly one bounded
 * `LlmClient.streamChat` call (see [PlanPlanner]'s own kdoc for why it never loops like
 * `com.aiopenpocket.agent.loop.ReactLoopRunner`).
 */
sealed interface PlanProposalResult {
  /**
   * A structurally valid candidate [version] the caller may now hand to
   * `com.aiopenpocket.agent.core.plan.persistence.PlanRepository.saveProposal` (Task 4 itself never
   * calls the repository — see [PlanPlanner]'s kdoc). [explanation] is the free text the model
   * streamed alongside its control call, if any — a human-readable summary for the UI, never itself
   * a source of plan content (design spec section 7: "Свободный текст модели может быть пояснением,
   * но не является источником исполнения").
   */
  data class Proposed(val version: PlanVersion, val explanation: String?) : PlanProposalResult

  /** The planner asked [question] instead of proposing a plan (`request_plan_information`). */
  data class NeedsInfo(val question: String) : PlanProposalResult

  /** A candidate was parsed but `com.aiopenpocket.agent.core.plan.validation.PlanValidator` (plus this file's own domain-template checks) rejected it — never persisted, never silently coerced into something valid. */
  data class Invalid(val issues: List<PlanValidationIssue>) : PlanProposalResult

  /**
   * The turn produced nothing usable: no control call at all (a plain-text-only reply), more than
   * one control call in the same turn, a call whose arguments are structurally malformed (missing/
   * wrong-typed required field — as opposed to a present-but-*empty* value, which
   * `PlanValidator`'s own `blank_*` rules handle instead, see [PlanControlReducer]), an unrecognized
   * call name, or a `com.aiopenpocket.agent.core.llm.StreamEvent.Error` from the provider. The
   * caller's own retry/backoff policy (not this class) decides what happens next.
   *
   * [message] уходит пользователю в баннер ошибки как есть — поэтому по-русски и без внутренностей
   * (имён control-тулзов, id агентов). Диагностика для логов — в [detail]; у ошибки провайдера её нет:
   * его текст уже пользовательский (`userFacingLlmError`) и идёт в [message] напрямую.
   */
  data class Failed(val message: String, val detail: String? = null) : PlanProposalResult
}

/**
 * Drives exactly one planning turn: assembles a [PlanPromptBuilder] prompt, hands the model ONLY
 * [PlanControlSchemas.ALL] (never a real action-tool schema — design spec section 7: "Planning
 * phase больше не принимает план как свободный markdown... Action tools planning phase не
 * получает"), collects the resulting stream, and folds it into a [PlanProposalResult] via
 * [PlanControlReducer].
 *
 * ## Why this is one bounded call, not a `com.aiopenpocket.agent.loop.ReactLoopRunner`-style loop
 *
 * A planning turn can never execute a tool (there are none to execute), so there is nothing to loop
 * on: the model either answers with exactly one control call, or it does not, in ONE
 * `LlmClient.streamChat` round-trip — mirroring how the OLD `rezhimy-agenta-plan-i-goal-ee2361`
 * planning phase was already `maxIterations = 1` (`com.aiopenpocket.agent.loop
 * .resolveTurnExecutionPlan`'s `planning` branch). [propose] does not itself decide whether to
 * re-prompt on [PlanProposalResult.Failed]/[PlanProposalResult.Invalid] — that policy belongs to
 * whoever wires this into the chat turn (Task 5/6/10), same separation of concerns as
 * `com.aiopenpocket.agent.core.plan.persistence.PlanRepository` never deciding on its own whether to
 * retry a rejected transition.
 *
 * ## Why [PlanPlanner] never calls `PlanRepository`
 *
 * This task's own file list declares [PlanPlanner]'s dependencies as `LlmClient`, `AgentResolver`,
 * `PlanValidator` and "templates" ([PlanTemplateRegistry]) — deliberately NOT
 * `com.aiopenpocket.agent.core.plan.persistence.PlanRepository|PlanDao`. [propose] therefore only
 * ever returns a [PlanProposalResult] VALUE; persisting a [PlanProposalResult.Proposed] (via
 * `PlanRepository.saveProposal`) is left entirely to whichever later task actually assembles the
 * `com.aiopenpocket.agent.core.plan.model.AgentPlan` aggregate this version belongs to (this class
 * does not even know a plan's `conversationId`/`AgentPlan.status` — only its [PlanVersion] content).
 * Keeping this fold pure-of-persistence is deliberate: `PlanControlReducer.reduce` below never
 * touches I/O at all, so tests exercise it (and the higher-level [propose]) without Room/Robolectric.
 *
 * ## Why [PlanHorizon] is a parameter, never something `submit_plan` lets the model choose
 *
 * `com.aiopenpocket.agent.core.plan.model.PlanVersion` itself carries no horizon field (only
 * `com.aiopenpocket.agent.core.plan.model.AgentPlan.horizon` does), and
 * `com.aiopenpocket.agent.core.plan.validation.PlanValidator.validate` already takes [PlanHorizon] as
 * an explicit caller-supplied argument rather than deriving it from the version. Since [propose]
 * never constructs an `AgentPlan` (see above), whoever calls [propose] necessarily already knows —
 * or has just decided — which horizon this plan is (a UI toggle, or the sole entry of
 * `PlanTemplate.supportedHorizons` for a single-horizon domain); asking the model to also
 * (re-)declare it in `submit_plan`'s arguments would create a second, potentially-conflicting source
 * of truth for a fact [PlanProposalResult.Proposed] has no field to carry back anyway. UNSURE
 * (flagged in the Task 4 report): the design doc never states this explicitly either way — Task
 * 5/6/10 should confirm this reading before wiring a caller.
 */
@SingleIn(AppScope::class)
@Inject
class PlanPlanner(
  private val llmClient: LlmClient,
  private val agentResolver: AgentResolver,
  private val templateRegistry: PlanTemplateRegistry,
  private val promptBuilder: PlanPromptBuilder,
) {

  /**
   * @param planId The plan aggregate this version belongs to. Not generated here — Task 4 never
   *   creates a `com.aiopenpocket.agent.core.plan.model.AgentPlan`, so a stable id must already exist
   *   before the first planning turn (the caller mints it once and reuses it across every
   *   NEEDS_INFO/revision round-trip).
   * @param agentId Resolved via [AgentResolver.resolve] exactly once; `null`, or a `null` resolution
   *   result, fails the whole call ([PlanProposalResult.Failed]) — `PlanValidator.validate` requires
   *   a real, non-null `AgentDefinition`, and this task does not invent a synthetic "every tool
   *   allowed" agent for the agent-less case (flagged in the Task 4 report as an open question for a
   *   later task, not silently designed around here).
   * @param existingVersion The current approved/pending version, when this call is a revision turn;
   *   `null` for a brand-new plan's first proposal. Threaded straight through to
   *   [PlanPromptBuilder.build] and to [PlanControlReducer.reduce], which derives the next version
   *   number from it (never trusting the model to self-report one).
   * @param relevantContext Optional, already-rendered profile/memory text (see [PlanPromptBuilder]'s
   *   kdoc for why [PlanPlanner] does not fetch this itself).
   */
  suspend fun propose(
    planId: String,
    agentId: String?,
    horizon: PlanHorizon,
    userMessage: String,
    existingVersion: PlanVersion? = null,
    relevantContext: String? = null,
    provider: LlmProvider = LlmProvider.OPENROUTER,
  ): PlanProposalResult {
    require(existingVersion == null || existingVersion.planId == planId) {
      "existingVersion.planId '${existingVersion?.planId}' does not match planId '$planId'."
    }

    val agent = agentResolver.resolve(agentId)
      ?: return PlanProposalResult.Failed(
        "Планы составляют агенты, а этот чат ни к одному не привязан (или агента удалили).",
        detail = "Planning requires a resolvable agent; '$agentId' did not resolve to one.",
      )

    val template = templateRegistry.getForAgent(agentId)
    if (template != null && horizon !in template.supportedHorizons) {
      return PlanProposalResult.Failed(
        "Этот агент не умеет составлять планы такого типа.",
        detail = "Horizon $horizon is not supported by agent '$agentId' (template '${template.id}' supports ${template.supportedHorizons}).",
      )
    }

    val systemPrompt = promptBuilder.build(
      agent = agent,
      horizon = horizon,
      template = template,
      existingVersion = existingVersion,
      relevantContext = relevantContext,
      userMessage = userMessage,
    )
    val messages = listOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = userMessage),
    )

    var explanation = ""
    val calls = mutableListOf<StreamEvent.ToolCall>()
    var streamError: String? = null
    llmClient.streamChat(messages, PlanControlSchemas.ALL, provider).collect { event ->
      when (event) {
        is StreamEvent.TextDelta -> explanation += event.content
        is StreamEvent.ToolCall -> calls += event
        is StreamEvent.Finish -> Unit
        is StreamEvent.Error -> streamError = event.message
      }
    }

    val error = streamError
    if (error != null) return PlanProposalResult.Failed(error)

    return PlanControlReducer.reduce(
      calls = calls,
      explanation = explanation.ifBlank { null },
      planId = planId,
      horizon = horizon,
      agent = agent,
      template = template,
      existingVersion = existingVersion,
    )
  }
}

/**
 * Pure fold from one planning turn's already-collected control calls to a [PlanProposalResult] — no
 * suspend, no I/O, no `LlmClient`/`AgentResolver` — every test builds a synthetic
 * `List<StreamEvent.ToolCall>` directly instead of driving a fake `LlmClient` stream through
 * [PlanPlanner.propose] for every scenario (a handful of [PlanPlanner]-level tests still do that, to
 * cover the collection wrapper itself — see `PlanPlannerTest`).
 */
object PlanControlReducer {

  fun reduce(
    calls: List<StreamEvent.ToolCall>,
    explanation: String?,
    planId: String,
    horizon: PlanHorizon,
    agent: AgentDefinition,
    template: PlanTemplate?,
    existingVersion: PlanVersion?,
  ): PlanProposalResult {
    if (calls.isEmpty()) {
      return PlanProposalResult.Failed(
        "Модель ответила текстом, а не планом — попробуй ещё раз или переформулируй задачу.",
        detail = "Planner turn ended without calling submit_plan, propose_plan_revision or request_plan_information.",
      )
    }
    if (calls.size > 1) {
      return PlanProposalResult.Failed(
        MALFORMED_PLAN_MESSAGE,
        detail = "Planner turn called ${calls.size} control tools at once (${calls.joinToString { it.name }}); exactly one is required.",
      )
    }

    val call = calls.single()
    return try {
      when (call.name) {
        PlanControlSchemas.REQUEST_PLAN_INFORMATION -> {
          val question = call.arguments.rawString("question") ?: throw PlanArgumentException("Missing 'question'.")
          if (question.isBlank()) throw PlanArgumentException("'question' must not be blank.")
          PlanProposalResult.NeedsInfo(question)
        }

        PlanControlSchemas.SUBMIT_PLAN, PlanControlSchemas.PROPOSE_PLAN_REVISION ->
          buildProposal(call, explanation, planId, horizon, agent, template, existingVersion)

        else -> PlanProposalResult.Failed(MALFORMED_PLAN_MESSAGE, detail = "Unknown planner control call '${call.name}'.")
      }
    } catch (e: PlanArgumentException) {
      PlanProposalResult.Failed(MALFORMED_PLAN_MESSAGE, detail = "Malformed ${call.name} arguments: ${e.message}")
    }
  }

  /** Модель ответила не по контракту control-тулзов — для пользователя это одна и та же ситуация. */
  private const val MALFORMED_PLAN_MESSAGE = "Модель прислала план не в том формате — попробуй ещё раз."

  private fun buildProposal(
    call: StreamEvent.ToolCall,
    explanation: String?,
    planId: String,
    horizon: PlanHorizon,
    agent: AgentDefinition,
    template: PlanTemplate?,
    existingVersion: PlanVersion?,
  ): PlanProposalResult {
    // Version numbering is derived in code, never trusted to the model (mirrors
    // PlanStateMachine.proposeRevision's own reasoning: "rejects a caller who tries to reuse/
    // decrement a version number instead of allocating a new one").
    val nextVersion = (existingVersion?.version ?: 0) + 1
    var candidate = parsePlanContent(call.arguments, planId, nextVersion)

    // Design spec section 7: required domain safety constraints are MERGED in, not merely checked —
    // the model does not have to perfectly restate them for them to end up on the plan.
    val requiredConstraints = template?.requiredConstraints.orEmpty()
    if (requiredConstraints.isNotEmpty()) {
      candidate = candidate.copy(constraints = (candidate.constraints + requiredConstraints).distinct())
    }

    if (existingVersion != null) {
      val revisionCheck = PlanStateMachine.proposeRevision(existingVersion, candidate)
      if (revisionCheck is PlanVersionResult.Rejected) {
        return PlanProposalResult.Failed(
          "Не получилось обновить план — попробуй ещё раз.",
          detail = revisionCheck.reason,
        )
      }
    }

    val issues = PlanValidator.validate(candidate, horizon, agent, requiredConstraints.toSet()) +
      forbiddenToolIssues(candidate, template)

    return if (PlanValidator.isValid(issues)) {
      PlanProposalResult.Proposed(candidate, explanation)
    } else {
      PlanProposalResult.Invalid(issues)
    }
  }

  /**
   * `PlanTemplate.forbiddenToolNames` enforced IN CODE, not left as prompt-only guidance
   * ([PlanPromptBuilder] also hides these names from the planner's tool list, but a model can still
   * hallucinate/ignore that) — this repo's house rule is to never trust a safety property to prompt
   * text alone. Reuses [PlanValidationIssue] (Task 2's frozen type) rather than inventing a parallel
   * issue type, so a caller branches on ONE combined list exactly as it already does for
   * [PlanValidator.validate]'s own output. UNSURE (flagged in the Task 4 report): Task 4's own Step
   * 5 text only says "merges required domain constraints, then calls PlanValidator" — enforcing
   * `forbiddenToolNames` here as well is this file's own extension, not a literal instruction.
   */
  private fun forbiddenToolIssues(version: PlanVersion, template: PlanTemplate?): List<PlanValidationIssue> {
    val forbidden = template?.forbiddenToolNames.orEmpty()
    if (forbidden.isEmpty()) return emptyList()
    return version.steps.sortedBy { it.id }.flatMap { step ->
      (step.expectedTools intersect forbidden).sorted().map { tool ->
        PlanValidationIssue(
          code = "forbidden_tool_in_step",
          message = "Step '${step.id}' expects tool '$tool', which this agent's domain template forbids for planning.",
          path = "${step.id}.expectedTools[$tool]",
        )
      }
    }
  }

  // --- argument parsing --------------------------------------------------------------------------
  //
  // Throws PlanArgumentException ONLY for structural problems: a required key that is entirely
  // absent, or present with the wrong JSON shape/an unrecognized enum value that the domain type
  // literally cannot represent (e.g. a criterion verifier `type` must be one of the known
  // VerifierType names — an unrecognized one cannot become a valid verifier). A
  // PRESENT-but-empty string (blank objective/step id/title/instruction/criterion description) is
  // intentionally let through as-is: PlanValidator already has dedicated blank_* rules for exactly
  // that, and re-deciding it here would bypass that frozen, tested logic.

  private fun parsePlanContent(arguments: Map<String, JsonElement>, planId: String, version: Int): PlanVersion {
    val objective = arguments.rawString("objective") ?: throw PlanArgumentException("Missing 'objective'.")
    val stepsJson = arguments["steps"] as? JsonArray ?: throw PlanArgumentException("Missing or non-array 'steps'.")
    val criteriaJson = arguments["criteria"] as? JsonArray ?: throw PlanArgumentException("Missing or non-array 'criteria'.")
    return PlanVersion(
      planId = planId,
      version = version,
      objective = objective,
      assumptions = arguments.rawStringList("assumptions"),
      constraints = arguments.rawStringList("constraints"),
      steps = stepsJson.mapIndexed { index, element -> parseStep(element, index) },
      criteria = criteriaJson.mapIndexed { index, element -> parseCriterion(element, index) },
      createdBy = PlanAuthor.AGENT,
      approvedAt = null,
    )
  }

  private fun parseStep(element: JsonElement, index: Int): PlanStep {
    val obj = element as? JsonObject ?: throw PlanArgumentException("steps[$index] is not an object.")
    val id = obj.rawString("id") ?: throw PlanArgumentException("steps[$index] is missing 'id'.")
    val title = obj.rawString("title") ?: throw PlanArgumentException("step '$id' is missing 'title'.")
    val instruction = obj.rawString("instruction") ?: throw PlanArgumentException("step '$id' is missing 'instruction'.")
    return PlanStep(
      id = id,
      title = title,
      instruction = instruction,
      dependsOn = obj.rawStringList("dependsOn").toSet(),
      expectedTools = obj.rawStringList("expectedTools").toSet(),
      schedule = parseSchedule(obj["schedule"], id),
    )
  }

  private fun parseSchedule(element: JsonElement?, stepId: String): PlanSchedule? {
    if (element == null || element is JsonNull) return null
    val obj = element as? JsonObject ?: throw PlanArgumentException("step '$stepId' has a non-object 'schedule'.")
    val kind = obj.rawString("kind")?.let { raw -> ScheduleKind.entries.find { it.name == raw } }
      ?: throw PlanArgumentException("step '$stepId' schedule has a missing/unrecognized 'kind'.")
    return PlanSchedule(
      kind = kind,
      delayMinutes = obj.rawLong("delayMinutes"),
      intervalMinutes = obj.rawLong("intervalMinutes"),
    )
  }

  private fun parseCriterion(element: JsonElement, index: Int): PlanCriterion {
    val obj = element as? JsonObject ?: throw PlanArgumentException("criteria[$index] is not an object.")
    val id = obj.rawString("id") ?: throw PlanArgumentException("criteria[$index] is missing 'id'.")
    val description = obj.rawString("description") ?: throw PlanArgumentException("criterion '$id' is missing 'description'.")
    val required = (obj["required"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
      ?: throw PlanArgumentException("criterion '$id' is missing boolean 'required'.")
    val verifierJson = obj["verifier"] as? JsonObject ?: throw PlanArgumentException("criterion '$id' is missing 'verifier'.")
    return PlanCriterion(id = id, description = description, verifier = parseVerifier(verifierJson, id), required = required)
  }

  private fun parseVerifier(obj: JsonObject, criterionId: String): CriterionVerifier {
    val type = obj.rawString("type")?.let { raw -> VerifierType.entries.find { it.name == raw } }
      ?: throw PlanArgumentException("criterion '$criterionId' verifier has a missing/unrecognized 'type'.")
    return CriterionVerifier(type = type, config = (obj["config"] as? JsonObject) ?: JsonObject(emptyMap()))
  }

  // --- structural-only JSON reading (see the big comment above "parsePlanContent") ---------------

  private fun Map<String, JsonElement>.rawString(key: String): String? = (this[key] as? JsonPrimitive)?.content

  private fun Map<String, JsonElement>.rawStringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

  private fun Map<String, JsonElement>.rawLong(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}

/** Thrown only by the private `parse*` helpers above; caught once in [PlanControlReducer.reduce] and turned into [PlanProposalResult.Failed] — never leaks past that point. */
private class PlanArgumentException(message: String) : Exception(message)
