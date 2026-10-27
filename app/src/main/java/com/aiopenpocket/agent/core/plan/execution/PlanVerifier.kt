package com.aiopenpocket.agent.core.plan.execution

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.plan.validation.PlanStateMachine
import com.aiopenpocket.agent.core.plan.validation.PlanStepCommand
import com.aiopenpocket.agent.core.plan.validation.StepTransitionResult
import com.aiopenpocket.agent.core.profile.stripToJsonObject
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Typed result of verifying one finished [PlanStep] run against its plan's
 * [com.aiopenpocket.agent.core.plan.model.PlanCriterion]s (design plan Task 8, Step 1's sealed
 * interface, reproduced here verbatim). [Proven.evidence] is exactly the NEW
 * [com.aiopenpocket.agent.core.plan.model.PlanEvidence] rows this verification round persisted — not
 * the plan's whole evidence history. The other four variants all describe a step that is NOT yet
 * provably done; [PlanVerifier.verify] maps each to a legal next
 * [com.aiopenpocket.agent.core.plan.model.StepStatus] (see [PlanVerifier.stepStatusFor]):
 * - [Retryable] — a deterministic check ran cleanly but found no proof yet (e.g. a failed tool call, a
 *   memory fact that does not exist yet, or the LLM judged a MODEL_ASSESSMENT criterion unmet). The
 *   step goes back to READY so a future attempt (within [PlanRunBudget.maxRetriesPerStep]) can try
 *   again.
 * - [NeedsInfo] — an unsatisfied REQUIRED criterion is [VerifierType.USER_CONFIRMATION]: no amount of
 *   retrying fixes this, only the user can. Maps to BLOCKED.
 * - [NeedsRevision] — an unsatisfied required criterion's verifier config is structurally invalid
 *   ([CriterionVerdict.InvalidConfig]) — a plan-authoring defect, matching design spec section 4's own
 *   "VERIFYING ... plan invalid → NEEDS_REVISION" edge. Maps to BLOCKED at the step level (this class
 *   never touches [com.aiopenpocket.agent.core.plan.model.PlanStatus] itself — see class kdoc "Scope").
 * - [Failed] — the verification MACHINERY itself broke (MODEL_ASSESSMENT's LLM call/response parsing),
 *   not "the criterion was judged unmet". Maps to FAILED.
 *
 * UNCERTAIN (flagged per this task's own instructions): the design doc does not literally specify this
 * four-way split for the "not yet proven" cases, only that [PlanVerifier] must exist and gate
 * COMPLETED on evidence. This is this task's own defensible reading, chosen so every
 * [VerificationOutcome] variant has one clear, reachable, testable trigger rather than leaving some
 * dead code. See [PlanVerifier]'s own class kdoc "Outcome priority" for the exact decision order when
 * more than one applies.
 */
sealed interface VerificationOutcome {
  data class Proven(val evidence: List<PlanEvidence>) : VerificationOutcome
  data class Retryable(val reason: String) : VerificationOutcome
  data class NeedsInfo(val question: String) : VerificationOutcome
  data class NeedsRevision(val reason: String) : VerificationOutcome
  data class Failed(val reason: String) : VerificationOutcome
}

/**
 * [PlanVerifier.verify]'s full return value: the typed [VerificationOutcome] plus the
 * [StepStatus] `PlanExecutor.recordOutcome` should persist for it. [stepStatus] is already validated
 * legal from [StepStatus.VERIFYING] by [PlanStateMachine.transitionStep] (see
 * [PlanVerifier.stepStatusFor]) — the caller never hand-picks a status the way the pre-Task-8
 * `recordOutcome` did.
 */
data class PlanVerificationResult(
  val outcome: VerificationOutcome,
  val stepStatus: StepStatus,
)

/**
 * Narrow port for a single [VerifierType.MEMORY_FACT] lookup — mirrors [PlanStepRunner]'s own
 * reasoning for a seam interface (see its kdoc): [PlanVerifier] only ever calls ONE
 * [MemoryRepository] method, so depending on the whole class (its Room/DAO graph) would force every
 * [PlanVerifier] test through Robolectric for no reason. Bound in production straight to
 * [MemoryRepository.getFactsByKeyPrefix] below.
 */
fun interface MemoryFactLookup {
  suspend fun getFactsByKeyPrefix(prefix: String): List<Fact>
}

/**
 * Narrow port for persisting one [PlanEvidence] row — the ONLY
 * [com.aiopenpocket.agent.core.plan.persistence.PlanRepository] method [PlanVerifier] ever calls.
 * Same "one-method seam so tests don't need the whole class" reasoning as [MemoryFactLookup]; unlike
 * [MemoryRepository], [PlanRepository] itself is not otherwise hard to construct, but this keeps
 * [PlanVerifier]'s own unit tests (design plan Task 8: "table-tested pure verdict logic w/ fake LLM +
 * fake repository") plain JVM tests with no Room/Robolectric at all.
 */
fun interface PlanEvidenceSink {
  suspend fun addEvidence(evidence: PlanEvidence)
}

/** Production bindings for the two narrow ports above — mirrors [PlanStepRunnerBindings]. */
@ContributesTo(AppScope::class)
@BindingContainer
object PlanVerifierPortBindings {

  @Provides
  @SingleIn(AppScope::class)
  fun provideMemoryFactLookup(memoryRepository: MemoryRepository): MemoryFactLookup =
    MemoryFactLookup(memoryRepository::getFactsByKeyPrefix)

  @Provides
  @SingleIn(AppScope::class)
  fun providePlanEvidenceSink(repository: PlanRepository): PlanEvidenceSink =
    PlanEvidenceSink(repository::addEvidence)
}

/**
 * Verifies ONE finished [PlanStep] run (design spec `2026-07-12-structured-plan-engine-design.md`
 * section 11, plan Task 8) — the seam `PlanExecutor.recordOutcome`'s Task 7 kdoc names explicitly:
 * "Task 8 replaces its 'clean finish → COMPLETED' branch with a real verifier call that only marks
 * COMPLETED once every required [PlanCriterion] has persisted evidence" — the plan's evidence gate,
 * never a free-text model claim of "done".
 *
 * ## Scope: step-level only, never touches [com.aiopenpocket.agent.core.plan.model.PlanStatus]
 *
 * [verify] decides whether ONE STEP reaches [StepStatus.COMPLETED]; it never calls
 * `PlanStateMachine.tryComplete`/writes [com.aiopenpocket.agent.core.plan.model.PlanStatus.COMPLETED]
 * itself. Design spec section 11: "Verifier не получает action tools и не изменяет план" — this class
 * has no path to [com.aiopenpocket.agent.core.plan.persistence.PlanRepository.saveProposal]/`approve`/
 * any whole-plan write. Whole-plan completion (the design's "evaluate whole-plan completion" transaction
 * step) is left to a future caller (`PlanRunner`, Task 10) that can see ALL steps' outcomes, not just
 * one.
 *
 * ## Which criteria get (re-)checked
 *
 * Only criteria [PlanSnapshot.evidence] does not YET reference by
 * [PlanEvidence.criterionId] — an already-proven criterion is never re-verified. This is deliberately
 * NOT scoped to "criteria this step owns" (design's own [PlanCriterion] kdoc: "Deliberately NOT tied to
 * a single [PlanStep.id]"): any step's run may be the one that happens to produce the tool call /
 * memory write / user confirmation a still-open criterion needed, regardless of which step's
 * instruction text originally motivated it.
 *
 * ## Outcome priority (when more than one unsatisfied required criterion applies)
 *
 * [decideOutcome] checks in this fixed order — structural plan problems first (fixing the config also
 * fixes everything reported after it), then "needs a human" (no code path resolves it), then hard
 * verification-machinery failure, then the generic default:
 * 1. any [CriterionVerdict.InvalidConfig] → [VerificationOutcome.NeedsRevision]
 * 2. any unsatisfied [VerifierType.USER_CONFIRMATION] → [VerificationOutcome.NeedsInfo]
 * 3. any [CriterionVerdict.VerificationFailed] (MODEL_ASSESSMENT LLM call/parse broke) → [VerificationOutcome.Failed]
 * 4. otherwise → [VerificationOutcome.Retryable]
 *
 * ## MODEL_ASSESSMENT: the only case that calls the LLM
 *
 * [checkModelAssessment] issues a single tool-less [LlmClient.streamChat] call (`tools = emptyList()`
 * — Task 8 required test "verifier receives zero action schemas"), asks it to return one strict JSON
 * verdict (`app/src/main/assets/prompts/plan/verifier.md`), and parses that with the SAME
 * fence-stripping/balanced-brace [stripToJsonObject] helper
 * [com.aiopenpocket.agent.core.profile.ExtractUserProfileUseCase] already uses for LLM JSON extraction
 * (reused as-is, `internal` across this module, rather than a second drifting copy). An unparseable or
 * stream-error response is [CriterionVerdict.VerificationFailed] (verification broke), never silently
 * treated as either a pass or a clean "not satisfied" — see [VerificationOutcome] kdoc.
 * [DeterministicVerifierRegistry] never sees a MODEL_ASSESSMENT criterion (its own `check` throws if
 * asked) — the plan's instructions: "Never allow it to prove a deterministic criterion".
 */
@SingleIn(AppScope::class)
class PlanVerifier @Inject constructor(
  private val evidenceSink: PlanEvidenceSink,
  private val memoryFacts: MemoryFactLookup,
  private val llmClient: LlmClient,
  private val assets: AssetSource,
) {
  private var clock: () -> Long = System::currentTimeMillis

  /** Test seam — mirrors [PlanRepository]/[PlanExecutor]'s own secondary constructor for a fixed time source. */
  constructor(
    evidenceSink: PlanEvidenceSink,
    memoryFacts: MemoryFactLookup,
    llmClient: LlmClient,
    assets: AssetSource,
    clock: () -> Long,
  ) : this(evidenceSink, memoryFacts, llmClient, assets) {
    this.clock = clock
  }

  /**
   * Verifies [step]'s just-finished [result] against [snapshot]'s plan-level criteria. Persists a new
   * [PlanEvidence] row (via [evidenceSink]) for every NOT-yet-proven criterion this round satisfies —
   * required or optional alike (Task 8 required test "optional criteria don't block completion": an
   * optional criterion's evidence is still worth recording, it just never appears in
   * [PlanStateMachine.unsatisfiedRequiredCriteria]'s gate). Never persists anything for an
   * ALREADY-proven criterion (no duplicate evidence rows for the same criterion+run).
   */
  suspend fun verify(
    snapshot: PlanSnapshot,
    step: PlanStep,
    result: AgentResult,
    provider: LlmProvider = LlmProvider.OPENROUTER,
  ): PlanVerificationResult {
    val criteria = snapshot.version.criteria
    val alreadyProvenIds = snapshot.evidence.mapNotNullTo(mutableSetOf()) { it.criterionId }
    val pending = criteria.filter { it.id !in alreadyProvenIds }

    val checks = pending.map { criterion -> checkCriterion(criterion, step, snapshot, result, provider) }
    val newEvidence = mutableListOf<PlanEvidence>()
    for (check in checks) {
      if (check is CriterionVerdict.Satisfied) {
        val evidence = PlanEvidence(
          id = UUID.randomUUID().toString(),
          planId = snapshot.plan.id,
          version = snapshot.version.version,
          criterionId = check.criterion.id,
          stepId = step.id,
          source = evidenceSourceFor(check.criterion.verifier.type),
          reference = check.reference,
          summary = check.summary,
          createdAt = clock(),
        )
        evidenceSink.addEvidence(evidence)
        newEvidence += evidence
      }
    }

    val allEvidence = snapshot.evidence + newEvidence
    val unsatisfiedRequired = PlanStateMachine.unsatisfiedRequiredCriteria(criteria, allEvidence)
    val outcome = decideOutcome(unsatisfiedRequired, checks, newEvidence)
    return PlanVerificationResult(outcome, stepStatusFor(outcome))
  }

  private suspend fun checkCriterion(
    criterion: PlanCriterion,
    step: PlanStep,
    snapshot: PlanSnapshot,
    result: AgentResult,
    provider: LlmProvider,
  ): CriterionVerdict {
    if (criterion.verifier.type == VerifierType.MODEL_ASSESSMENT) {
      return checkModelAssessment(criterion, step, snapshot, result, provider)
    }
    val context = DeterministicVerificationContext(
      toolTrace = result.toolTrace,
      matchingMemoryFacts = if (criterion.verifier.type == VerifierType.MEMORY_FACT) fetchMatchingFacts(criterion) else emptyList(),
    )
    return DeterministicVerifierRegistry.check(criterion, context)
  }

  private suspend fun fetchMatchingFacts(criterion: PlanCriterion): List<Fact> {
    val key = criterion.verifier.config.rawString(MEMORY_KEY_CONFIG) ?: return emptyList()
    return memoryFacts.getFactsByKeyPrefix(key)
  }

  /** Tool-less LLM call — see class kdoc "MODEL_ASSESSMENT". Never throws: a stream/parse failure becomes [CriterionVerdict.VerificationFailed]. */
  private suspend fun checkModelAssessment(
    criterion: PlanCriterion,
    step: PlanStep,
    snapshot: PlanSnapshot,
    result: AgentResult,
    provider: LlmProvider,
  ): CriterionVerdict {
    val messages = buildModelAssessmentMessages(loadSystemPrompt(), snapshot, step, criterion, result)
    val text = StringBuilder()
    var streamError: String? = null
    llmClient.streamChat(messages, emptyList(), provider).collect { event ->
      when (event) {
        // Размышление модели — не вердикт: в нём бывает черновой JSON, который разобрался бы как ответ.
        is StreamEvent.TextDelta -> if (!event.reasoning) text.append(event.content)
        is StreamEvent.Error -> streamError = event.message
        else -> Unit
      }
    }
    streamError?.let { return CriterionVerdict.VerificationFailed(criterion, "MODEL_ASSESSMENT LLM call failed: $it") }

    val verdict = parseModelVerdict(text.toString())
      ?: return CriterionVerdict.VerificationFailed(
        criterion,
        "MODEL_ASSESSMENT response was not valid JSON: ${text.toString().take(SUMMARY_MAX_CHARS)}",
      )
    return if (verdict.satisfied) {
      CriterionVerdict.Satisfied(
        criterion,
        reference = "model_assessment:${criterion.id}:${clock()}",
        summary = verdict.reasoning.ifBlank { tr("Модель посчитала критерий выполненным.", "The model judged the criterion met.") },
      )
    } else {
      CriterionVerdict.Unsatisfied(
        criterion,
        verdict.reasoning.ifBlank { tr("Модель посчитала критерий НЕ выполненным.", "The model judged the criterion NOT met.") },
      )
    }
  }

  private fun loadSystemPrompt(): String = buildString {
    appendLine(assets.load(SYSTEM_BASE_ASSET) ?: DEFAULT_SYSTEM_BASE)
    appendLine()
    appendLine(assets.load(VERIFIER_PROMPT_ASSET) ?: DEFAULT_VERIFIER_PROMPT)
  }

  companion object {
    private const val SYSTEM_BASE_ASSET = "prompts/system_base.md"
    private const val VERIFIER_PROMPT_ASSET = "prompts/plan/verifier.md"
    private const val MEMORY_KEY_CONFIG = "key"
    private const val SUMMARY_MAX_CHARS = 200
    private const val RESULT_TEXT_MAX_CHARS = 4000

    // Fallbacks mirror PlanExecutor's own reasoning: a missing/unreadable asset must never leave the
    // verifier with NO instructions at all, even though in practice the real asset always ships.
    private const val DEFAULT_SYSTEM_BASE = "Ты — локальный AI-агент на Android-устройстве пользователя."
    private const val DEFAULT_VERIFIER_PROMPT =
      "Ты проверяешь, доказывает ли показанный результат конкретный критерий готовности. Отвечай " +
        "СТРОГО JSON-объектом {\"satisfied\": true|false, \"reasoning\": \"...\"}, без пояснений вне " +
        "JSON. Если сомневаешься — считай критерий НЕ выполненным."

    private val modelAssessmentJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun evidenceSourceFor(type: VerifierType): EvidenceSource = when (type) {
      VerifierType.TOOL_RESULT -> EvidenceSource.TOOL_RESULT
      VerifierType.MEMORY_FACT -> EvidenceSource.MEMORY_FACT
      VerifierType.USER_CONFIRMATION -> EvidenceSource.USER_CONFIRMATION
      VerifierType.MODEL_ASSESSMENT -> EvidenceSource.MODEL_ASSESSMENT
    }

    /** Pure decision logic — see class kdoc "Outcome priority". No I/O; directly table-testable with hand-built [CriterionVerdict] lists. */
    internal fun decideOutcome(
      unsatisfiedRequired: List<PlanCriterion>,
      checks: List<CriterionVerdict>,
      newEvidence: List<PlanEvidence>,
    ): VerificationOutcome {
      if (unsatisfiedRequired.isEmpty()) return VerificationOutcome.Proven(newEvidence)

      val blocking = unsatisfiedRequired.mapNotNull { required -> checks.find { it.criterion.id == required.id } }

      blocking.filterIsInstance<CriterionVerdict.InvalidConfig>().let { invalid ->
        if (invalid.isNotEmpty()) {
          return VerificationOutcome.NeedsRevision(
            "Некорректная конфигурация верификатора у критериев: ${invalid.joinToString { it.criterion.id }}.",
          )
        }
      }

      blocking.filter { it.criterion.verifier.type == VerifierType.USER_CONFIRMATION }.let { needsUser ->
        if (needsUser.isNotEmpty()) {
          return VerificationOutcome.NeedsInfo(
            "Нужно подтверждение пользователя по критериям: ${needsUser.joinToString { it.criterion.description }}.",
          )
        }
      }

      blocking.filterIsInstance<CriterionVerdict.VerificationFailed>().let { failed ->
        if (failed.isNotEmpty()) {
          return VerificationOutcome.Failed(
            "Проверка не смогла выполниться: ${failed.joinToString { "${it.criterion.id}: ${it.reason}" }}.",
          )
        }
      }

      return VerificationOutcome.Retryable(
        "Критерии пока не подтверждены: ${blocking.joinToString { describeUnsatisfied(it) }}.",
      )
    }

    private fun describeUnsatisfied(verdict: CriterionVerdict): String = when (verdict) {
      is CriterionVerdict.Unsatisfied -> "${verdict.criterion.id} (${verdict.reason})"
      is CriterionVerdict.Satisfied -> verdict.criterion.id
      is CriterionVerdict.InvalidConfig -> "${verdict.criterion.id} (${verdict.reason})"
      is CriterionVerdict.VerificationFailed -> "${verdict.criterion.id} (${verdict.reason})"
    }

    /**
     * Maps [outcome] to the [PlanStepCommand] that would produce it, then validates the transition via
     * [PlanStateMachine.transitionStep] FROM [StepStatus.VERIFYING] rather than hand-picking a status —
     * matching how every other write in this engine ([com.aiopenpocket.agent.core.plan.persistence
     * .PlanRepository]'s own kdoc) is state-machine output, never assigned directly. Always
     * [StepTransitionResult.Allowed] in practice (VERIFYING legally allows Prove/Retry/Block/FailStep
     * for every [VerificationOutcome] variant) — the [error] branch exists only as a self-documenting
     * assertion, matching [PlanRepository.allowedStatus]'s own "throw, never silently no-op" convention.
     */
    internal fun stepStatusFor(outcome: VerificationOutcome): StepStatus {
      val command = when (outcome) {
        is VerificationOutcome.Proven -> PlanStepCommand.Prove
        is VerificationOutcome.Retryable -> PlanStepCommand.Retry(outcome.reason)
        is VerificationOutcome.NeedsInfo -> PlanStepCommand.Block(outcome.question)
        is VerificationOutcome.NeedsRevision -> PlanStepCommand.Block(outcome.reason)
        is VerificationOutcome.Failed -> PlanStepCommand.FailStep(outcome.reason)
      }
      return when (val transition = PlanStateMachine.transitionStep(StepStatus.VERIFYING, command)) {
        is StepTransitionResult.Allowed -> transition.status
        is StepTransitionResult.Rejected -> error(
          "Unreachable: StepStatus.VERIFYING must legally allow $command, but PlanStateMachine rejected it: ${transition.error.reason}",
        )
      }
    }

    private fun buildModelAssessmentMessages(
      systemPrompt: String,
      snapshot: PlanSnapshot,
      step: PlanStep,
      criterion: PlanCriterion,
      result: AgentResult,
    ): List<ChatMessage> {
      val userContent = buildString {
        appendLine("Цель плана: ${escapeForPrompt(snapshot.version.objective)}")
        appendLine("Шаг [${step.id}] «${escapeForPrompt(step.title)}»: ${escapeForPrompt(step.instruction)}")
        appendLine()
        appendLine("Критерий для оценки: ${escapeForPrompt(criterion.description)}")
        appendLine()
        appendLine("Результат исполнения шага (текст модели):")
        appendLine(escapeForPrompt(result.text.take(RESULT_TEXT_MAX_CHARS)))
        if (result.toolTrace.isNotEmpty()) {
          appendLine()
          appendLine("Вызовы инструментов в рамках шага:")
          result.toolTrace.forEach { call -> appendLine("- ${call.toolName}: ${escapeForPrompt(call.result.toString().take(SUMMARY_MAX_CHARS))}") }
        }
      }
      return listOf(
        ChatMessage(role = "system", content = systemPrompt),
        ChatMessage(role = "user", content = userContent),
      )
    }

    /** Same minimal defense as [PlanExecutor]'s own `escapeForPrompt` — plan/step/criterion text can reflect tool/user content, never fully trusted free text. */
    private fun escapeForPrompt(text: String): String = text.replace("<", "&lt;").replace(">", "&gt;")

    private data class ModelAssessmentVerdict(val satisfied: Boolean, val reasoning: String)

    /**
     * Manual field reading (not a strict `@Serializable` decode) for the SAME reason
     * [com.aiopenpocket.agent.core.profile.ExtractUserProfileUseCase.field] uses one: a model can
     * return `"satisfied": "true"` (string) as easily as `true` (boolean), and a strict decode would
     * throw/lose the whole verdict over that. Returns `null` only when no JSON object could be found or
     * parsed at all (see [checkModelAssessment]: that maps to [CriterionVerdict.VerificationFailed]) —
     * a parseable object with an unrecognized/missing "satisfied" value safely defaults to `false`
     * (unproven unless clearly proven, matching the whole engine's evidence-first philosophy).
     */
    private fun parseModelVerdict(raw: String): ModelAssessmentVerdict? {
      val jsonText = stripToJsonObject(raw) ?: return null
      val obj = try {
        modelAssessmentJson.parseToJsonElement(jsonText) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return null
      val satisfied = (obj["satisfied"] as? JsonPrimitive)?.content?.trim()?.equals("true", ignoreCase = true) == true
      val reasoning = (obj["reasoning"] as? JsonPrimitive)?.content?.trim().orEmpty()
      return ModelAssessmentVerdict(satisfied = satisfied, reasoning = reasoning)
    }
  }
}
