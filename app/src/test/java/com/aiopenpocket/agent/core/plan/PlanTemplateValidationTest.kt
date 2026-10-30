package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.template.AssetPlanTemplateRegistry
import com.aiopenpocket.agent.core.plan.template.PlanTemplate
import com.aiopenpocket.agent.core.plan.validation.PlanValidationSeverity
import com.aiopenpocket.agent.core.plan.validation.PlanValidator
import com.aiopenpocket.agent.core.prompt.AssetSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 13 contract/validation tests for the three bundled domain [PlanTemplate] assets (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 13: budget/nutrition/languages). Reads the
 * REAL `app/src/main/assets/plan_templates` files through the REAL [AssetPlanTemplateRegistry]
 * (Task 4) via a `java.io.File`-backed [AssetSource] — same "read bundled JSON on the plain JVM, no
 * Robolectric" posture `com.aiopenpocket.agent.core.lesson.LessonAssetsValidationTest` already uses
 * for lesson assets, and the same "pure JUnit, no Android" posture as [PlanValidatorTest]/
 * [PlanPlannerTest] (whose fake [AssetSource] never had a real file behind it).
 *
 * These templates are prompt guidance consumed by [com.aiopenpocket.agent.core.plan.planner
 * .PlanPromptBuilder]/[com.aiopenpocket.agent.core.plan.planner.PlanPlanner] (already wired since
 * Task 4 — `PlanPlanner.propose` already calls `PlanTemplateRegistry.getForAgent`, so simply adding
 * these three assets is what "activates" domain guidance for budget/nutrition/languages plans; no new
 * wiring code is needed or added here) — templates are NOT plans themselves and never auto-publish
 * (plan doc Task 13 Step 1: "Templates guide planning; they are not plans and do not auto-publish").
 * [PlanTemplate.suggestedSteps]/[PlanTemplate.suggestedCriteria] therefore carry no concrete step
 * ids/`dependsOn` graph of their own (see `PlanStepTemplate`'s own kdoc: "no concrete step ids or
 * dependsOn graph to offer"). To still honor this task's own requirement that a template's suggested
 * content is realistic enough to pass [PlanValidator], [syntheticVersion] below deterministically
 * projects one template's suggested content into a real, throwaway [PlanVersion] and feeds that
 * through the actual frozen validator — never a parallel re-implementation of its rules.
 */
class PlanTemplateValidationTest {

  // --- reading the REAL bundled assets, no fakes (mirrors LessonAssetsValidationTest) -------------

  // Gradle unit tests run from the module directory (app/); fall back to the project root.
  private val assetsDir: File = sequenceOf(File("src/main/assets"), File("app/src/main/assets"))
    .firstOrNull { it.isDirectory }
    ?: error("Could not find src/main/assets; working directory: ${File(".").absolutePath}")

  private val registry = AssetPlanTemplateRegistry(
    assets = AssetSource { path -> File(assetsDir, path).takeIf { it.isFile }?.readText() },
  )

  private suspend fun loadTemplate(agentId: String): PlanTemplate =
    registry.getForAgent(agentId) ?: error("plan_templates/$agentId.json failed to load or parse")

  // --- Step 1: asset contract - id/agentId/horizons/constraints/steps/criteria/forbidden tools ----

  @Test
  fun `budget template loads with the expected shape`() = runTest {
    val template = loadTemplate("budget")
    assertEquals("budget-v1", template.id)
    assertEquals("budget", template.agentId)
    assertEquals(setOf(PlanHorizon.SINGLE_RUN, PlanHorizon.PROGRAM), template.supportedHorizons)
    assertTrue("budget template should declare required constraints", template.requiredConstraints.isNotEmpty())
    assertTrue("budget template should suggest steps", template.suggestedSteps.isNotEmpty())
    assertTrue("budget template should suggest criteria", template.suggestedCriteria.isNotEmpty())
    assertTrue("budget template should not need a forbidden tool (no financial-transaction tool exists)", template.forbiddenToolNames.isEmpty())
  }

  @Test
  fun `languages template loads with the expected shape`() = runTest {
    val template = loadTemplate("languages")
    assertEquals("languages-v1", template.id)
    assertEquals("languages", template.agentId)
    assertEquals(setOf(PlanHorizon.PROGRAM), template.supportedHorizons)
    assertTrue("languages template should declare required constraints", template.requiredConstraints.isNotEmpty())
    assertTrue("languages template should suggest steps", template.suggestedSteps.isNotEmpty())
    assertTrue("languages template should suggest criteria", template.suggestedCriteria.isNotEmpty())
    assertTrue("languages template should have no forbidden tools", template.forbiddenToolNames.isEmpty())
  }

  // --- registry: registered + retrievable; a miss stays a normal null (Task 4's own contract) ------

  @Test
  fun `all three domain templates are registered and retrievable by agent id`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      assertTrue("no template registered for '$agentId'", registry.getForAgent(agentId) != null)
    }
  }

  @Test
  fun `an agent with no domain template resolves to null, not an error`() = runTest {
    // "tasks" is a real preset agent (MiniAgentCatalog) that intentionally has no plan_templates
    // asset of its own yet - a miss here is normal, per PlanTemplateRegistry's own kdoc.
    assertNull(registry.getForAgent("tasks"))
    assertNull(registry.getForAgent("unknown-agent-id"))
  }

  // --- Step 5: "match an existing agent ID" --------------------------------------------------------

  @Test
  fun `each template's agentId matches an existing preset agent`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      assertEquals(agentId, template.agentId)
      assertTrue(
        "agentId '${template.agentId}' does not resolve to a real preset agent",
        MiniAgentCatalog.get(template.agentId) != null,
      )
    }
  }

  // --- Step 5: "reference registered tools only" ---------------------------------------------------

  @Test
  fun `suggested and forbidden tools are all known, registered tool names`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      val referenced = template.suggestedSteps.flatMap { it.suggestedExpectedTools }.toSet() + template.forbiddenToolNames
      val unknown = referenced - AgentValidator.KNOWN_TOOL_NAMES
      assertTrue("template '${template.id}' references unregistered tool(s): $unknown", unknown.isEmpty())
    }
  }

  @Test
  fun `suggested tools stay within the bound agent's own tool scope`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      val agent = MiniAgentCatalog.get(agentId)!!.toDefinition()
      val referenced = template.suggestedSteps.flatMap { it.suggestedExpectedTools }.toSet()
      val outOfScope = referenced - agent.toolNames
      assertTrue(
        "template '${template.id}' suggests tool(s) outside agent '$agentId' scope: $outOfScope",
        outOfScope.isEmpty(),
      )
    }
  }

  // --- Step 5: "contain at least one required constraint" ------------------------------------------

  @Test
  fun `each template declares at least one non-blank required constraint`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      assertTrue("template '${template.id}' has no required constraints", template.requiredConstraints.isNotEmpty())
      assertTrue(
        "template '${template.id}' has a blank required constraint",
        template.requiredConstraints.all { it.isNotBlank() },
      )
    }
  }

  // --- Step 5: "use both SINGLE_RUN and/or PROGRAM as declared" + PROGRAM implies a schedule --------

  @Test
  fun `supported horizons are non-empty, and every PROGRAM-capable template has a scheduled suggested step`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      assertTrue("template '${template.id}' declares no supported horizon", template.supportedHorizons.isNotEmpty())
      if (PlanHorizon.PROGRAM in template.supportedHorizons) {
        assertTrue(
          "PROGRAM-capable template '${template.id}' has no suggested step carrying a schedule",
          template.suggestedSteps.any { it.suggestedSchedule != null },
        )
      }
    }
  }

  // --- Step 5: "contain no cyclic suggested dependencies" -------------------------------------------

  @Test
  fun `suggested steps project onto an acyclic step chain`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      val horizon = template.supportedHorizons.first()
      val agent = MiniAgentCatalog.get(agentId)!!.toDefinition()
      val version = syntheticVersion(template, horizon)
      val issues = PlanValidator.validate(version, horizon, agent, template.requiredConstraints.toSet())
      assertTrue(
        "template '${template.id}' suggested steps form a dependency cycle: ${issues.filter { it.code == "dependency_cycle" }}",
        issues.none { it.code == "dependency_cycle" },
      )
    }
  }

  // --- "every template MUST pass PlanValidator": full structural/scope check, per declared horizon -

  @Test
  fun `a plan built from each template's suggested content passes PlanValidator for every horizon it declares`() = runTest {
    for (agentId in DOMAIN_AGENT_IDS) {
      val template = loadTemplate(agentId)
      val agent = MiniAgentCatalog.get(agentId)!!.toDefinition()
      for (horizon in template.supportedHorizons) {
        val version = syntheticVersion(template, horizon)
        val issues = PlanValidator.validate(version, horizon, agent, template.requiredConstraints.toSet())
        val errors = issues.filter { it.severity == PlanValidationSeverity.ERROR }
        assertTrue(
          "template '${template.id}' failed PlanValidator under $horizon: $errors",
          PlanValidator.isValid(issues),
        )
      }
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Deterministically projects a template's guidance-only [PlanTemplate.suggestedSteps]/
   * [PlanTemplate.suggestedCriteria] onto a real, throwaway [PlanVersion] purely so [PlanValidator]
   * (the actual frozen production rules, never a re-implementation) can confirm the template's
   * suggested shape is structurally sound: a linear `stepN depends on step(N-1)` chain (suggested
   * steps carry no `dependsOn` of their own), tools taken verbatim from each step's
   * `suggestedExpectedTools`, and a schedule kept ONLY under [PlanHorizon.PROGRAM] (a
   * [PlanHorizon.SINGLE_RUN] plan may never schedule a step at all, so budget's mixed-horizon
   * template must not carry its recurring-checkpoint schedule into a SINGLE_RUN check). Criterion
   * verifiers get a synthesized, non-empty placeholder [JsonObject] config for every non-
   * [VerifierType.MODEL_ASSESSMENT] type - suggested criteria intentionally carry no config of their
   * own (that only exists once a real plan names a concrete tool/memory key), so this is the minimal
   * fixture needed to exercise [PlanValidator]'s "criterion is checkable" rule at all.
   */
  private fun syntheticVersion(template: PlanTemplate, horizon: PlanHorizon): PlanVersion {
    val steps = template.suggestedSteps.mapIndexed { index, stepTemplate ->
      PlanStep(
        id = "step-$index",
        title = stepTemplate.title,
        instruction = stepTemplate.instruction,
        dependsOn = if (index == 0) emptySet() else setOf("step-${index - 1}"),
        expectedTools = stepTemplate.suggestedExpectedTools,
        schedule = stepTemplate.suggestedSchedule.takeIf { horizon == PlanHorizon.PROGRAM },      )
    }
    val criteria = template.suggestedCriteria.mapIndexed { index, criterionTemplate ->
      PlanCriterion(
        id = "criterion-$index",
        description = criterionTemplate.description,
        verifier = CriterionVerifier(
          type = criterionTemplate.suggestedVerifierType,
          config = if (criterionTemplate.suggestedVerifierType == VerifierType.MODEL_ASSESSMENT) {
            JsonObject(emptyMap())
          } else {
            JsonObject(mapOf("source" to JsonPrimitive("plan-template-contract-test")))
          },
        ),
        required = criterionTemplate.required,
      )
    }
    return PlanVersion(
      planId = "template-check-${template.id}",
      version = 1,
      objective = "Synthetic objective projected from template '${template.id}' for PlanValidator contract testing.",
      assumptions = emptyList(),
      constraints = template.requiredConstraints,
      steps = steps,
      criteria = criteria,
      createdBy = PlanAuthor.AGENT,
      approvedAt = null,
    )
  }

  private companion object {
    val DOMAIN_AGENT_IDS = listOf("budget", "languages")
  }
}
