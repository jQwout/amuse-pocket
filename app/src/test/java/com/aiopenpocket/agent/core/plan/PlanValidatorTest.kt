package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanSchedule
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.ScheduleKind
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.validation.PlanValidationSeverity
import com.aiopenpocket.agent.core.plan.validation.PlanValidator
import com.aiopenpocket.agent.loop.AgentMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanValidator] is a pure function set — no Robolectric, no I/O needed. Covers the design spec's
 * `submit_plan` rules (`2026-07-12-structured-plan-engine-design.md` section 7) and the exact
 * scenarios `task-2-brief.md` Step 1 asks for: blank objective, duplicate ids, a dangling
 * dependency, a dependency cycle, an out-of-scope tool, the step/criteria count ceilings, the
 * PROGRAM schedule rules (min interval, heartbeat capability), an uncheckable criterion verifier, a
 * removed domain safety constraint, and the PROGRAM/SINGLE_RUN structural split.
 */
class PlanValidatorTest {

  // --- fixtures --------------------------------------------------------------------------------

  private fun agent(toolNames: Set<String> = setOf("memory_tool", "heartbeat")) = AgentDefinition(
    id = "test-agent",
    source = AgentSource.USER,
    name = "Test agent",
    description = "Agent used by PlanValidator tests.",
    iconKey = "sparkles",
    systemPrompt = "You are a test agent.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:test-agent:", includeSharedProfile = true),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 1,
    status = AgentStatus.PUBLISHED,
  )

  /** A verifier whose config actually identifies something concrete to check, unless [type] is [VerifierType.MODEL_ASSESSMENT] (deliberately configless). */
  private fun checkableVerifier(type: VerifierType = VerifierType.TOOL_RESULT) = CriterionVerifier(
    type = type,
    config = if (type == VerifierType.MODEL_ASSESSMENT) {
      JsonObject(emptyMap())
    } else {
      JsonObject(mapOf("toolName" to JsonPrimitive("memory_tool")))
    },
  )

  private fun criterion(
    id: String,
    required: Boolean = true,
    verifier: CriterionVerifier = checkableVerifier(),
    description: String = "criterion $id",
  ) = PlanCriterion(id = id, description = description, verifier = verifier, required = required)

  private fun step(
    id: String,
    dependsOn: Set<String> = emptySet(),
    expectedTools: Set<String> = emptySet(),
    schedule: PlanSchedule? = null,
    title: String = "step $id",
    instruction: String = "do $id",
  ) = PlanStep(
    id = id,
    title = title,
    instruction = instruction,
    dependsOn = dependsOn,
    expectedTools = expectedTools,
    schedule = schedule,  )

  private fun version(
    steps: List<PlanStep> = listOf(step("s1")),
    criteria: List<PlanCriterion> = listOf(criterion("c1")),
    objective: String = "objective",
    constraints: List<String> = emptyList(),
  ) = PlanVersion(
    planId = "plan-1",
    version = 1,
    objective = objective,
    assumptions = emptyList(),
    constraints = constraints,
    steps = steps,
    criteria = criteria,
    createdBy = PlanAuthor.AGENT,
    approvedAt = null,
  )

  private fun validate(
    version: PlanVersion,
    horizon: PlanHorizon = PlanHorizon.SINGLE_RUN,
    agent: AgentDefinition = agent(),
    requiredConstraints: Set<String> = emptySet(),
  ) = PlanValidator.validate(version, horizon, agent, requiredConstraints)

  // --- valid plans pass --------------------------------------------------------------------------

  @Test
  fun `a well-formed SINGLE_RUN plan passes with no issues`() {
    val issues = validate(version())
    assertTrue("expected no issues, got: $issues", issues.isEmpty())
  }

  @Test
  fun `a well-formed PROGRAM plan with a scheduled step and heartbeat capability passes`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))))

    val issues = validate(plan, horizon = PlanHorizon.PROGRAM, agent = agent(toolNames = setOf("heartbeat")))

    assertTrue("expected no issues, got: $issues", issues.isEmpty())
  }

  // --- objective -----------------------------------------------------------------------------

  @Test
  fun `blank objective is rejected`() {
    assertTrue(validate(version(objective = "   ")).any { it.code == "blank_objective" })
  }

  // --- duplicate ids ---------------------------------------------------------------------------

  @Test
  fun `duplicate step ids are rejected`() {
    assertTrue(validate(version(steps = listOf(step("s1"), step("s1")))).any { it.code == "duplicate_step_id" })
  }

  @Test
  fun `duplicate criterion ids are rejected`() {
    assertTrue(validate(version(criteria = listOf(criterion("c1"), criterion("c1")))).any { it.code == "duplicate_criterion_id" })
  }

  // --- dependency graph shape ------------------------------------------------------------------

  @Test
  fun `a dangling dependency is rejected and reports the exact missing edge`() {
    val issues = validate(version(steps = listOf(step("s1", dependsOn = setOf("ghost")))))

    val issue = issues.single { it.code == "dangling_dependency" }
    assertEquals("s1 -> ghost", issue.path)
  }

  @Test
  fun `a two-step dependency cycle is detected and its concrete path reported`() {
    val issues = validate(
      version(steps = listOf(step("s1", dependsOn = setOf("s2")), step("s2", dependsOn = setOf("s1")))),
    )

    val issue = issues.single { it.code == "dependency_cycle" }
    assertEquals(listOf("s1", "s2", "s1"), issue.path!!.split(" -> "))
  }

  @Test
  fun `a three-step dependency cycle is detected`() {
    val issues = validate(
      version(
        steps = listOf(
          step("s1", dependsOn = setOf("s2")),
          step("s2", dependsOn = setOf("s3")),
          step("s3", dependsOn = setOf("s1")),
        ),
      ),
    )

    assertTrue(issues.any { it.code == "dependency_cycle" })
  }

  @Test
  fun `a step graph with independent chains and no cycle passes clean`() {
    // Two independent chains, neither depending on the other - legal (no single required root).
    val issues = validate(
      version(steps = listOf(step("collect-categories"), step("collect-limit"), step("weekly-check", dependsOn = setOf("collect-categories", "collect-limit")))),
    )

    assertTrue("expected no graph-shape issues, got: $issues", issues.none { it.code == "dependency_cycle" || it.code == "dangling_dependency" })
  }

  // --- tool scope ------------------------------------------------------------------------------

  @Test
  fun `an expected tool outside agent scope is flagged`() {
    val issues = validate(
      version(steps = listOf(step("s1", expectedTools = setOf("email")))),
      agent = agent(toolNames = setOf("memory_tool")),
    )

    val issue = issues.single { it.code == "tool_outside_agent_scope" }
    assertEquals("s1.expectedTools[email]", issue.path)
  }

  @Test
  fun `a planner control tool name is never a legal step tool, and is not double-reported as out-of-scope`() {
    val issues = validate(
      version(steps = listOf(step("s1", expectedTools = setOf("submit_plan")))),
      agent = agent(toolNames = setOf("memory_tool")), // realistic: a real agent never actually holds a planner control tool name
    )

    assertTrue(issues.any { it.code == "planner_control_tool_in_step" })
    assertTrue(issues.none { it.code == "tool_outside_agent_scope" }) // not double-reported
  }

  @Test
  fun `a tool inside agent scope is not flagged`() {
    val issues = validate(
      version(steps = listOf(step("s1", expectedTools = setOf("memory_tool")))),
      agent = agent(toolNames = setOf("memory_tool", "heartbeat")),
    )

    assertTrue(issues.none { it.code == "tool_outside_agent_scope" })
  }

  // --- size limits -----------------------------------------------------------------------------

  @Test
  fun `more than 30 steps is rejected`() {
    val steps = (1..31).map { step("s$it") }
    assertTrue(validate(version(steps = steps, criteria = emptyList())).any { it.code == "too_many_steps" })
  }

  @Test
  fun `exactly 30 steps is allowed`() {
    val steps = (1..30).map { step("s$it") }
    assertTrue(validate(version(steps = steps, criteria = emptyList())).none { it.code == "too_many_steps" })
  }

  @Test
  fun `more than 20 criteria is rejected`() {
    val criteria = (1..21).map { criterion("c$it") }
    assertTrue(validate(version(criteria = criteria)).any { it.code == "too_many_criteria" })
  }

  @Test
  fun `exactly 20 criteria is allowed`() {
    val criteria = (1..20).map { criterion("c$it") }
    assertTrue(validate(version(criteria = criteria)).none { it.code == "too_many_criteria" })
  }

  // --- PROGRAM schedule rules ------------------------------------------------------------------

  @Test
  fun `a PROGRAM periodic interval below 15 minutes is rejected`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 14))))

    val issues = validate(plan, horizon = PlanHorizon.PROGRAM, agent = agent(toolNames = setOf("heartbeat")))

    assertTrue(issues.any { it.code == "periodic_interval_too_short" })
  }

  @Test
  fun `a PROGRAM periodic interval of exactly 15 minutes is allowed`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 15))))

    val issues = validate(plan, horizon = PlanHorizon.PROGRAM, agent = agent(toolNames = setOf("heartbeat")))

    assertTrue(issues.none { it.code == "periodic_interval_too_short" })
  }

  @Test
  fun `a PROGRAM scheduled step is rejected when the agent lacks the heartbeat capability`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.PERIODIC, intervalMinutes = 60))))

    val issues = validate(plan, horizon = PlanHorizon.PROGRAM, agent = agent(toolNames = setOf("memory_tool")))

    assertTrue(issues.any { it.code == "missing_heartbeat_capability" })
  }

  @Test
  fun `a SINGLE_RUN plan cannot schedule a step, even with heartbeat capability`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = 30))))

    val issues = validate(plan, horizon = PlanHorizon.SINGLE_RUN, agent = agent(toolNames = setOf("heartbeat")))

    assertTrue(issues.any { it.code == "schedule_on_single_run_plan" })
  }

  @Test
  fun `a PROGRAM ONE_TIME schedule with no delayMinutes is rejected`() {
    val plan = version(steps = listOf(step("s1", schedule = PlanSchedule(kind = ScheduleKind.ONE_TIME, delayMinutes = null))))

    val issues = validate(plan, horizon = PlanHorizon.PROGRAM, agent = agent(toolNames = setOf("heartbeat")))

    assertTrue(issues.any { it.code == "invalid_schedule_delay" })
  }

  // --- criterion verifier checkability -----------------------------------------------------------

  @Test
  fun `a required criterion without a valid verifier is an ERROR`() {
    val emptyVerifier = CriterionVerifier(type = VerifierType.TOOL_RESULT, config = JsonObject(emptyMap()))

    val issues = validate(version(criteria = listOf(criterion("c1", required = true, verifier = emptyVerifier))))

    val issue = issues.single { it.code == "criterion_verifier_not_checkable" }
    assertEquals(PlanValidationSeverity.ERROR, issue.severity)
  }

  @Test
  fun `a non-required criterion without a valid verifier is only a WARNING`() {
    val emptyVerifier = CriterionVerifier(type = VerifierType.MEMORY_FACT, config = JsonObject(emptyMap()))

    val issues = validate(version(criteria = listOf(criterion("c1", required = false, verifier = emptyVerifier))))

    val issue = issues.single { it.code == "criterion_verifier_not_checkable" }
    assertEquals(PlanValidationSeverity.WARNING, issue.severity)
  }

  @Test
  fun `a required criterion backed only by MODEL_ASSESSMENT is a WARNING, not an ERROR, and does not block validity`() {
    val issues = validate(
      version(criteria = listOf(criterion("c1", required = true, verifier = checkableVerifier(VerifierType.MODEL_ASSESSMENT)))),
    )

    val issue = issues.single { it.code == "required_criterion_weak_evidence" }
    assertEquals(PlanValidationSeverity.WARNING, issue.severity)
    assertTrue(PlanValidator.isValid(issues))
  }

  @Test
  fun `MODEL_ASSESSMENT never needs config to count as checkable`() {
    val modelAssessment = CriterionVerifier(type = VerifierType.MODEL_ASSESSMENT, config = JsonObject(emptyMap()))

    val issues = validate(version(criteria = listOf(criterion("c1", required = false, verifier = modelAssessment))))

    assertTrue(issues.none { it.code == "criterion_verifier_not_checkable" })
  }

  // --- domain safety constraints -----------------------------------------------------------------

  @Test
  fun `a default domain safety constraint removed from the plan is rejected`() {
    val required = setOf("no medical diagnosis", "no financial transactions")

    val issues = validate(version(constraints = listOf("no medical diagnosis")), requiredConstraints = required)

    val issue = issues.single { it.code == "required_constraint_removed" }
    assertEquals("no financial transactions", issue.path)
  }

  @Test
  fun `all required domain safety constraints present produces no issue`() {
    val required = setOf("no medical diagnosis")

    val issues = validate(version(constraints = listOf("no medical diagnosis", "extra constraint")), requiredConstraints = required)

    assertTrue(issues.none { it.code == "required_constraint_removed" })
  }

  // --- isValid -----------------------------------------------------------------------------------

  @Test
  fun `isValid is false whenever any ERROR-severity issue is present`() {
    assertFalse(PlanValidator.isValid(validate(version(objective = ""))))
  }

  @Test
  fun `isValid is true for an empty issue list`() {
    assertTrue(PlanValidator.isValid(emptyList()))
  }
}
