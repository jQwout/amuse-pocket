package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.validation.PlanChangeClassifier
import com.aiopenpocket.agent.core.plan.validation.PlanChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanChangeClassifier] is a pure diff — no Robolectric, no I/O needed. Covers exactly
 * `task-2-brief.md` Step 2's required scenarios (objective/constraints/criteria changed; step
 * add/remove/reorder; expectedTools/schedule/completionRule changed -> MATERIAL; description-only
 * -> COSMETIC; identical -> no change) plus this task's own explicit "dependencies" addition to the
 * material field list, and the deliberate "assumptions is cosmetic" reading documented on
 * [PlanChangeClassifier]'s own kdoc.
 */
class PlanChangeClassifierTest {

  // --- fixtures --------------------------------------------------------------------------------

  private fun criterion(id: String, description: String = "criterion $id", required: Boolean = true) = PlanCriterion(
    id = id,
    description = description,
    verifier = CriterionVerifier(type = VerifierType.TOOL_RESULT),
    required = required,
  )

  private fun step(
    id: String,
    dependsOn: Set<String> = emptySet(),
    expectedTools: Set<String> = emptySet(),
    schedule: PlanSchedule? = null,
    completionRule: CompletionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
    title: String = "step $id",
    instruction: String = "do $id",
  ) = PlanStep(
    id = id,
    title = title,
    instruction = instruction,
    dependsOn = dependsOn,
    expectedTools = expectedTools,
    schedule = schedule,
    completionRule = completionRule,
  )

  private fun version(
    steps: List<PlanStep> = listOf(step("s1"), step("s2")),
    criteria: List<PlanCriterion> = listOf(criterion("c1")),
    objective: String = "objective",
    assumptions: List<String> = emptyList(),
    constraints: List<String> = emptyList(),
    approvedAt: Long? = 1_000L,
  ) = PlanVersion(
    planId = "plan-1",
    version = 1,
    objective = objective,
    assumptions = assumptions,
    constraints = constraints,
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = approvedAt,
  )

  // --- no change -----------------------------------------------------------------------------

  @Test
  fun `identical content classifies as NONE with no changed fields`() {
    val v = version()

    val result = PlanChangeClassifier.classify(v, v.copy(version = 2, approvedAt = null))

    assertEquals(PlanChangeKind.NONE, result.kind)
    assertTrue(result.changedFields.isEmpty())
  }

  @Test
  fun `version, planId, createdBy and approvedAt are ignored entirely - not this classifier's job`() {
    // PlanStateMachine.proposeRevision is what validates planId/version-sequencing legality;
    // classify() is a pure content diff and must not re-derive that check.
    val approved = version(approvedAt = 1_000L)
    val draftWithDifferentIdentity = approved.copy(planId = "different-plan-id", version = 99, approvedAt = null, createdBy = PlanAuthor.USER)

    val result = PlanChangeClassifier.classify(approved, draftWithDifferentIdentity)

    assertEquals(PlanChangeKind.NONE, result.kind)
  }

  // --- MATERIAL: design spec section 6's literal list -------------------------------------------

  @Test
  fun `objective changed is MATERIAL`() {
    val result = PlanChangeClassifier.classify(version(objective = "old"), version(objective = "new"))

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_OBJECTIVE), result.changedFields)
  }

  @Test
  fun `constraints changed is MATERIAL`() {
    val result = PlanChangeClassifier.classify(
      version(constraints = listOf("no medical diagnosis")),
      version(constraints = listOf("no medical diagnosis", "no financial transactions")),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_CONSTRAINTS), result.changedFields)
  }

  @Test
  fun `criteria changed is MATERIAL`() {
    val result = PlanChangeClassifier.classify(
      version(criteria = listOf(criterion("c1"))),
      version(criteria = listOf(criterion("c1"), criterion("c2"))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_CRITERIA), result.changedFields)
  }

  @Test
  fun `a criterion description-only edit is still MATERIAL - criteria has no wording carve-out the way steps do`() {
    val result = PlanChangeClassifier.classify(
      version(criteria = listOf(criterion("c1", description = "old wording"))),
      version(criteria = listOf(criterion("c1", description = "new wording"))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_CRITERIA), result.changedFields)
  }

  @Test
  fun `adding a step is MATERIAL`() {
    val result = PlanChangeClassifier.classify(version(steps = listOf(step("s1"))), version(steps = listOf(step("s1"), step("s2"))))

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEPS), result.changedFields)
  }

  @Test
  fun `removing a step is MATERIAL`() {
    val result = PlanChangeClassifier.classify(version(steps = listOf(step("s1"), step("s2"))), version(steps = listOf(step("s1"))))

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEPS), result.changedFields)
  }

  @Test
  fun `reordering steps is MATERIAL even though the same set of steps is present`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1"), step("s2"))),
      version(steps = listOf(step("s2"), step("s1"))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEPS), result.changedFields)
  }

  @Test
  fun `a step's dependsOn changing is MATERIAL - this task's own explicit addition beyond the brief's literal list`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1"), step("s2"))),
      version(steps = listOf(step("s1"), step("s2", dependsOn = setOf("s1")))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_DEPENDS_ON), result.changedFields)
  }

  @Test
  fun `a step's expectedTools changing is MATERIAL`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1", expectedTools = setOf("memory_tool")))),
      version(steps = listOf(step("s1", expectedTools = setOf("memory_tool", "heartbeat")))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_EXPECTED_TOOLS), result.changedFields)
  }

  @Test
  fun `a step's schedule changing is MATERIAL`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60)))),
      version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 120)))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_SCHEDULE), result.changedFields)
  }

  @Test
  fun `a step's completionRule changing is MATERIAL`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1", completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED)))),
      version(steps = listOf(step("s1", completionRule = CompletionRule(type = CompletionRuleType.USER_CONFIRMED)))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_COMPLETION_RULE), result.changedFields)
  }

  // --- COSMETIC: wording/description-only -------------------------------------------------------

  @Test
  fun `a step's title-instruction wording changing alone is COSMETIC`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1", title = "Old title", instruction = "Old instruction"))),
      version(steps = listOf(step("s1", title = "New title", instruction = "New instruction"))),
    )

    assertEquals(PlanChangeKind.COSMETIC, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_WORDING), result.changedFields)
  }

  @Test
  fun `assumptions-only changes are COSMETIC`() {
    val result = PlanChangeClassifier.classify(
      version(assumptions = listOf("user income is roughly 5000")),
      version(assumptions = listOf("user income is roughly 5500")),
    )

    assertEquals(PlanChangeKind.COSMETIC, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_ASSUMPTIONS), result.changedFields)
  }

  @Test
  fun `wording and assumptions changing together are still only COSMETIC`() {
    val result = PlanChangeClassifier.classify(
      version(assumptions = listOf("a"), steps = listOf(step("s1", title = "old"))),
      version(assumptions = listOf("b"), steps = listOf(step("s1", title = "new"))),
    )

    assertEquals(PlanChangeKind.COSMETIC, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_ASSUMPTIONS, PlanChangeClassifier.FIELD_STEP_WORDING), result.changedFields)
  }

  // --- material always wins when mixed with cosmetic ----------------------------------------------

  @Test
  fun `a material field change alongside a cosmetic one still classifies MATERIAL, and both are reported`() {
    val result = PlanChangeClassifier.classify(
      version(steps = listOf(step("s1", title = "old", expectedTools = emptySet()))),
      version(steps = listOf(step("s1", title = "new", expectedTools = setOf("memory_tool")))),
    )

    assertEquals(PlanChangeKind.MATERIAL, result.kind)
    assertEquals(setOf(PlanChangeClassifier.FIELD_STEP_WORDING, PlanChangeClassifier.FIELD_STEP_EXPECTED_TOOLS), result.changedFields)
  }
}
