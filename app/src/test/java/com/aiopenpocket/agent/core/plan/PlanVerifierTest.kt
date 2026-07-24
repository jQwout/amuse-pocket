package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.plan.execution.CriterionVerdict
import com.aiopenpocket.agent.core.plan.execution.DeterministicVerificationContext
import com.aiopenpocket.agent.core.plan.execution.DeterministicVerifierRegistry
import com.aiopenpocket.agent.core.plan.execution.MemoryFactLookup
import com.aiopenpocket.agent.core.plan.execution.PlanEvidenceSink
import com.aiopenpocket.agent.core.plan.execution.PlanVerifier
import com.aiopenpocket.agent.core.plan.execution.VerificationOutcome
import com.aiopenpocket.agent.core.plan.model.AgentPlan
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.EvidenceSource
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanEvidence
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSnapshot
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentResult
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ToolTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8: [DeterministicVerifierRegistry] (pure dispatch, no fakes needed) and [PlanVerifier]
 * (fake [PlanEvidenceSink]/[MemoryFactLookup]/[LlmClient]/[AssetSource] — plain JVM, no Room/
 * Robolectric at all, matching the plan's own "table-tested pure verdict logic w/ fake LLM + fake
 * repository" instruction). Covers every bullet in the plan's Task 8 "Required tests" list plus this
 * task's own instructions (see individual test names/comments below for the mapping).
 */
class PlanVerifierTest {

  // --- fixtures --------------------------------------------------------------------------------------

  private fun plan(id: String = "plan-1") = AgentPlan(
    id = id, agentId = "agent-1", conversationId = "conv-1", horizon = PlanHorizon.SINGLE_RUN,
    status = PlanStatus.ACTIVE, approvedVersion = 1, activeStepId = "step-1", createdAt = 0L, updatedAt = 0L,
  )

  private fun step(id: String = "step-1") = PlanStep(
    id = id, title = "Заголовок", instruction = "Инструкция шага", dependsOn = emptySet(), expectedTools = emptySet(),
    schedule = null,
  )

  private fun version(criteria: List<PlanCriterion>, objective: String = "Цель плана") = PlanVersion(
    planId = "plan-1", version = 1, objective = objective, assumptions = emptyList(), constraints = emptyList(),
    steps = listOf(step()), criteria = criteria, createdBy = PlanAuthor.AGENT, approvedAt = 1L,
  )

  private fun snapshot(criteria: List<PlanCriterion>, evidence: List<PlanEvidence> = emptyList()) =
    PlanSnapshot(plan = plan(), version = version(criteria = criteria), stepStates = emptyList(), evidence = evidence)

  private fun criterion(
    type: VerifierType,
    id: String = "c1",
    config: JsonObject = JsonObject(emptyMap()),
    required: Boolean = true,
  ) = PlanCriterion(id = id, description = "Критерий $id", verifier = CriterionVerifier(type = type, config = config), required = required)

  private fun toolResultCriterion(id: String = "c1", toolName: String = "memory_tool", required: Boolean = true) =
    criterion(type = VerifierType.TOOL_RESULT, id = id, config = buildJsonObject { put("toolName", toolName) }, required = required)

  private fun memoryFactCriterion(id: String = "c1", key: String = "budget.limit", required: Boolean = true) =
    criterion(type = VerifierType.MEMORY_FACT, id = id, config = buildJsonObject { put("key", key) }, required = required)

  private fun userConfirmationCriterion(id: String = "c1", required: Boolean = true) =
    criterion(type = VerifierType.USER_CONFIRMATION, id = id, config = buildJsonObject { put("question", "Подтвердите?") }, required = required)

  private fun modelAssessmentCriterion(id: String = "c1", required: Boolean = true) =
    criterion(type = VerifierType.MODEL_ASSESSMENT, id = id, required = required)

  private fun agentResult(text: String = "ok", toolTrace: List<ToolTrace> = emptyList()) =
    AgentResult(text = text, toolTrace = toolTrace, state = AgentState.FINISHED, iterations = 1)

  private fun existingEvidence(criterionId: String, source: EvidenceSource = EvidenceSource.TOOL_RESULT) = PlanEvidence(
    id = "ev-$criterionId", planId = "plan-1", version = 1, criterionId = criterionId, stepId = "step-1",
    source = source, reference = "ref-$criterionId", summary = "уже подтверждено", createdAt = 1L,
  )

  // --- fakes -----------------------------------------------------------------------------------------

  private class FakeEvidenceSink : PlanEvidenceSink {
    val recorded = mutableListOf<PlanEvidence>()
    override suspend fun addEvidence(evidence: PlanEvidence) {
      recorded += evidence
    }
  }

  private class FakeMemoryFactLookup(private val factsByExactKey: Map<String, List<Fact>> = emptyMap()) : MemoryFactLookup {
    var lastPrefix: String? = null
      private set

    override suspend fun getFactsByKeyPrefix(prefix: String): List<Fact> {
      lastPrefix = prefix
      return factsByExactKey[prefix] ?: emptyList()
    }
  }

  /** Scripted single-call fake — one `streamChat` invocation replays [events] in order, mirroring [ReflectionOrchestratorTest]'s own `ScriptedLlm`. */
  private class ScriptedLlm(private val events: List<StreamEvent>) : LlmClient {
    var callCount = 0
      private set
    var lastTools: List<FunctionSchema>? = null
      private set

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      callCount++
      lastTools = tools
      return flowOf(*events.toTypedArray())
    }
  }

  /** No test in this file expects [PlanVerifier] to call the LLM unless it explicitly wires a [ScriptedLlm] in — this fake fails loudly if it is ever reached by mistake (e.g. a deterministic criterion accidentally routed to MODEL_ASSESSMENT handling). */
  private object UnusedLlmClient : LlmClient {
    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> =
      throw IllegalStateException("LlmClient must not be called for a non-MODEL_ASSESSMENT criterion.")
  }

  private object NoAssets : AssetSource {
    override fun load(path: String): String? = null
  }

  private fun verifier(
    sink: PlanEvidenceSink = FakeEvidenceSink(),
    memoryFacts: MemoryFactLookup = FakeMemoryFactLookup(),
    llmClient: LlmClient = UnusedLlmClient,
  ) = PlanVerifier(sink, memoryFacts, llmClient, NoAssets) { 999_000L }

  // --- DeterministicVerifierRegistry: pure, no fakes needed -------------------------------------------

  @Test
  fun `TOOL_RESULT is satisfied only by a Success call to the exact configured tool (successful expected tool produces TOOL_RESULT evidence)`() {
    val c = toolResultCriterion(toolName = "memory_tool")
    val trace = listOf(
      ToolTrace(iteration = 2, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Success("сохранено"), timestamp = 123L),
    )

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = trace))

    val satisfied = verdict as? CriterionVerdict.Satisfied ?: error("expected Satisfied, got $verdict")
    assertEquals("tool_trace:memory_tool#2@123", satisfied.reference)
    assertTrue(satisfied.summary.contains("сохранено"))
  }

  @Test
  fun `TOOL_RESULT is NOT satisfied by an Error call to the same tool (failed tool does not prove criterion)`() {
    val c = toolResultCriterion(toolName = "memory_tool")
    val trace = listOf(ToolTrace(iteration = 1, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Error("boom")))

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = trace))

    assertTrue(verdict is CriterionVerdict.Unsatisfied)
  }

  @Test
  fun `TOOL_RESULT is Unsatisfied when no call to the configured tool exists at all`() {
    val c = toolResultCriterion(toolName = "memory_tool")

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList()))

    assertTrue(verdict is CriterionVerdict.Unsatisfied)
  }

  @Test
  fun `TOOL_RESULT is InvalidConfig when config has no toolName`() {
    val c = criterion(type = VerifierType.TOOL_RESULT, config = JsonObject(emptyMap()))

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList()))

    assertTrue(verdict is CriterionVerdict.InvalidConfig)
  }

  @Test
  fun `MEMORY_FACT is satisfied only when a fact with the exact configured key exists (memory fact reference must exist)`() {
    val c = memoryFactCriterion(key = "budget.limit")
    val fact = Fact(id = "f1", key = "budget.limit", value = "50000", category = "budget", createdAt = 1L, updatedAt = 1L)

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList(), matchingMemoryFacts = listOf(fact)))

    val satisfied = verdict as? CriterionVerdict.Satisfied ?: error("expected Satisfied, got $verdict")
    assertEquals("memory_fact:f1", satisfied.reference)
  }

  @Test
  fun `MEMORY_FACT is Unsatisfied when no matching fact exists yet`() {
    val c = memoryFactCriterion(key = "budget.limit")

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList(), matchingMemoryFacts = emptyList()))

    assertTrue(verdict is CriterionVerdict.Unsatisfied)
  }

  @Test
  fun `MEMORY_FACT does not accept a fact whose key only shares a prefix`() {
    val c = memoryFactCriterion(key = "budget.limit")
    val overMatched = Fact(id = "f1", key = "budget.limit.food", value = "1000", category = "budget", createdAt = 1L, updatedAt = 1L)

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList(), matchingMemoryFacts = listOf(overMatched)))

    assertTrue("a prefix match is not an exact key match", verdict is CriterionVerdict.Unsatisfied)
  }

  @Test
  fun `USER_CONFIRMATION is never satisfied by DeterministicVerifierRegistry (user confirmation required when verifier says USER_CONFIRMATION)`() {
    val c = userConfirmationCriterion()

    val verdict = DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList()))

    assertTrue(verdict is CriterionVerdict.Unsatisfied)
  }

  @Test
  fun `check throws for MODEL_ASSESSMENT (never allow it to prove a deterministic criterion)`() {
    val c = modelAssessmentCriterion()

    assertThrows(IllegalArgumentException::class.java) {
      DeterministicVerifierRegistry.check(c, DeterministicVerificationContext(toolTrace = emptyList()))
    }
  }

  // --- PlanVerifier.decideOutcome / stepStatusFor: pure, table-tested --------------------------------

  @Test
  fun `decideOutcome is Proven with the newly satisfied evidence when no required criterion is unsatisfied`() {
    val newEvidence = listOf(existingEvidence("c1"))

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = emptyList(), checks = emptyList(), newEvidence = newEvidence)

    assertEquals(VerificationOutcome.Proven(newEvidence), outcome)
  }

  @Test
  fun `decideOutcome is NeedsRevision when an unsatisfied required criterion has InvalidConfig (outcome priority 1)`() {
    val c = toolResultCriterion()
    val checks = listOf(CriterionVerdict.InvalidConfig(c, "no toolName"))

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = listOf(c), checks = checks, newEvidence = emptyList())

    assertTrue(outcome is VerificationOutcome.NeedsRevision)
  }

  @Test
  fun `decideOutcome is NeedsInfo when an unsatisfied required criterion is USER_CONFIRMATION (outcome priority 2)`() {
    val c = userConfirmationCriterion()
    val checks = listOf(CriterionVerdict.Unsatisfied(c, "needs the user"))

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = listOf(c), checks = checks, newEvidence = emptyList())

    assertTrue(outcome is VerificationOutcome.NeedsInfo)
  }

  @Test
  fun `decideOutcome is Failed when an unsatisfied required criterion's check is VerificationFailed (outcome priority 3)`() {
    val c = modelAssessmentCriterion()
    val checks = listOf(CriterionVerdict.VerificationFailed(c, "llm broke"))

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = listOf(c), checks = checks, newEvidence = emptyList())

    assertTrue(outcome is VerificationOutcome.Failed)
  }

  @Test
  fun `decideOutcome defaults to Retryable for a cleanly-unsatisfied required criterion (outcome priority 4, default)`() {
    val c = toolResultCriterion()
    val checks = listOf(CriterionVerdict.Unsatisfied(c, "no call yet"))

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = listOf(c), checks = checks, newEvidence = emptyList())

    assertTrue(outcome is VerificationOutcome.Retryable)
  }

  @Test
  fun `decideOutcome prioritizes InvalidConfig over an also-present USER_CONFIRMATION criterion`() {
    val badConfig = toolResultCriterion(id = "bad")
    val needsUser = userConfirmationCriterion(id = "user")
    val checks = listOf(
      CriterionVerdict.InvalidConfig(badConfig, "no toolName"),
      CriterionVerdict.Unsatisfied(needsUser, "needs the user"),
    )

    val outcome = PlanVerifier.decideOutcome(unsatisfiedRequired = listOf(badConfig, needsUser), checks = checks, newEvidence = emptyList())

    assertTrue("InvalidConfig must win over USER_CONFIRMATION per the documented priority order", outcome is VerificationOutcome.NeedsRevision)
  }

  @Test
  fun `stepStatusFor maps every VerificationOutcome variant to its legal StepStatus`() {
    assertEquals(StepStatus.COMPLETED, PlanVerifier.stepStatusFor(VerificationOutcome.Proven(emptyList())))
    assertEquals(StepStatus.READY, PlanVerifier.stepStatusFor(VerificationOutcome.Retryable("not yet")))
    assertEquals(StepStatus.BLOCKED, PlanVerifier.stepStatusFor(VerificationOutcome.NeedsInfo("confirm?")))
    assertEquals(StepStatus.BLOCKED, PlanVerifier.stepStatusFor(VerificationOutcome.NeedsRevision("bad config")))
    assertEquals(StepStatus.FAILED, PlanVerifier.stepStatusFor(VerificationOutcome.Failed("llm broke")))
  }

  // --- PlanVerifier.verify(): fake LLM + fake evidence sink + fake memory lookup ----------------------

  @Test
  fun `verify persists exactly one PlanEvidence for a satisfied criterion`() = runTest {
    val c = toolResultCriterion()
    val sink = FakeEvidenceSink()
    val trace = listOf(ToolTrace(iteration = 1, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Success("ok")))

    val result = verifier(sink = sink).verify(snapshot(criteria = listOf(c)), step(), agentResult(toolTrace = trace))

    assertTrue(result.outcome is VerificationOutcome.Proven)
    assertEquals(StepStatus.COMPLETED, result.stepStatus)
    assertEquals("exactly one PlanEvidence, not zero and not a duplicate", 1, sink.recorded.size)
    val evidence = sink.recorded.single()
    assertEquals(c.id, evidence.criterionId)
    assertEquals("step-1", evidence.stepId)
    assertEquals(EvidenceSource.TOOL_RESULT, evidence.source)
  }

  @Test
  fun `verify blocks COMPLETED when a required criterion has no evidence (required criterion without evidence blocks COMPLETED)`() = runTest {
    val c = toolResultCriterion()
    val sink = FakeEvidenceSink()

    val result = verifier(sink = sink).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertFalse(result.outcome is VerificationOutcome.Proven)
    assertTrue("a required criterion with no evidence never yields COMPLETED", result.stepStatus != StepStatus.COMPLETED)
    assertTrue(sink.recorded.isEmpty())
  }

  @Test
  fun `verify does not re-check or duplicate evidence for a criterion the snapshot already proves`() = runTest {
    val c = toolResultCriterion()
    val sink = FakeEvidenceSink()

    val result = verifier(sink = sink).verify(snapshot(criteria = listOf(c), evidence = listOf(existingEvidence(c.id))), step(), agentResult())

    assertTrue(result.outcome is VerificationOutcome.Proven)
    assertEquals(StepStatus.COMPLETED, result.stepStatus)
    assertTrue("must not persist a second evidence row for an already-proven criterion", sink.recorded.isEmpty())
  }

  @Test
  fun `verify does not block completion on an unmet OPTIONAL criterion (optional criteria don't block completion)`() = runTest {
    val optional = toolResultCriterion(id = "opt-1", toolName = "never_called_tool", required = false)

    val result = verifier().verify(snapshot(criteria = listOf(optional)), step(), agentResult())

    assertEquals(StepStatus.COMPLETED, result.stepStatus)
  }

  @Test
  fun `verify still records evidence for a satisfied OPTIONAL criterion even though it never gates completion`() = runTest {
    val optional = toolResultCriterion(id = "opt-1", toolName = "memory_tool", required = false)
    val sink = FakeEvidenceSink()
    val trace = listOf(ToolTrace(iteration = 1, toolName = "memory_tool", arguments = emptyMap(), result = ToolResult.Success("ok")))

    verifier(sink = sink).verify(snapshot(criteria = listOf(optional)), step(), agentResult(toolTrace = trace))

    assertEquals(1, sink.recorded.size)
    assertEquals(optional.id, sink.recorded.single().criterionId)
  }

  @Test
  fun `verify never routes a TOOL_RESULT criterion through the LLM (never allow it to prove a deterministic criterion)`() = runTest {
    val c = toolResultCriterion() // UnusedLlmClient (the verifier() default) throws if ever called
    val result = verifier().verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertTrue(result.outcome !is VerificationOutcome.Proven)
  }

  @Test
  fun `verify fetches memory facts only for MEMORY_FACT criteria, keyed by the criterion's config`() = runTest {
    val c = memoryFactCriterion(key = "budget.limit")
    val fact = Fact(id = "f1", key = "budget.limit", value = "50000", category = "budget", createdAt = 1L, updatedAt = 1L)
    val memoryLookup = FakeMemoryFactLookup(mapOf("budget.limit" to listOf(fact)))

    val result = verifier(memoryFacts = memoryLookup).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertEquals(StepStatus.COMPLETED, result.stepStatus)
    assertEquals("budget.limit", memoryLookup.lastPrefix)
  }

  @Test
  fun `verify sends zero tool schemas to the LLM for a MODEL_ASSESSMENT criterion (verifier receives zero action schemas)`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.TextDelta("""{"satisfied": true, "reasoning": "ok"}""")))

    verifier(llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertEquals(1, llm.callCount)
    assertTrue("MODEL_ASSESSMENT must call the LLM with zero tool schemas", llm.lastTools.orEmpty().isEmpty())
  }

  @Test
  fun `verify marks a satisfied MODEL_ASSESSMENT criterion's evidence with MODEL_ASSESSMENT source (MODEL_ASSESSMENT is marked weak)`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.TextDelta("""{"satisfied": true, "reasoning": "выполнено по тексту шага"}""")))
    val sink = FakeEvidenceSink()

    val result = verifier(sink = sink, llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertEquals(StepStatus.COMPLETED, result.stepStatus)
    assertEquals(1, sink.recorded.size)
    assertEquals(EvidenceSource.MODEL_ASSESSMENT, sink.recorded.single().source)
  }

  @Test
  fun `verify does NOT complete when MODEL_ASSESSMENT judges the criterion unmet`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.TextDelta("""{"satisfied": false, "reasoning": "не хватает данных"}""")))

    val result = verifier(llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertTrue(result.outcome is VerificationOutcome.Retryable)
    assertEquals(StepStatus.READY, result.stepStatus)
    assertTrue((result.outcome as VerificationOutcome.Retryable).reason.isNotBlank())
  }

  @Test
  fun `verify treats a MODEL_ASSESSMENT stream error as Failed, not a clean Unsatisfied (explanatory reason, non-blank)`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.Error("network down")))

    val result = verifier(llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertTrue(result.outcome is VerificationOutcome.Failed)
    assertEquals(StepStatus.FAILED, result.stepStatus)
    assertTrue((result.outcome as VerificationOutcome.Failed).reason.isNotBlank())
  }

  @Test
  fun `verify treats an unparseable MODEL_ASSESSMENT response as Failed`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.TextDelta("это не JSON вообще, а обычный текст")))

    val result = verifier(llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertTrue(result.outcome is VerificationOutcome.Failed)
  }

  @Test
  fun `verify tolerates a fenced MODEL_ASSESSMENT JSON response (markdown code fence around the object)`() = runTest {
    val c = modelAssessmentCriterion()
    val llm = ScriptedLlm(listOf(StreamEvent.TextDelta("```json\n{\"satisfied\": true, \"reasoning\": \"ok\"}\n```")))

    val result = verifier(llmClient = llm).verify(snapshot(criteria = listOf(c)), step(), agentResult())

    assertEquals(StepStatus.COMPLETED, result.stepStatus)
  }
}
