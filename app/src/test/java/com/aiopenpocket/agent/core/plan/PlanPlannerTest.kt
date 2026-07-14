package com.aiopenpocket.agent.core.plan

// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentResolver
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.plan.model.CompletionRule
import com.aiopenpocket.agent.core.plan.model.CompletionRuleType
import com.aiopenpocket.agent.core.plan.model.CriterionVerifier
import com.aiopenpocket.agent.core.plan.model.PlanAuthor
import com.aiopenpocket.agent.core.plan.model.PlanCriterion
import com.aiopenpocket.agent.core.plan.model.PlanHorizon
import com.aiopenpocket.agent.core.plan.model.PlanStep
import com.aiopenpocket.agent.core.plan.model.PlanVersion
import com.aiopenpocket.agent.core.plan.model.VerifierType
import com.aiopenpocket.agent.core.plan.planner.PlanControlSchemas
import com.aiopenpocket.agent.core.plan.planner.PlanPlanner
import com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder
import com.aiopenpocket.agent.core.plan.planner.PlanProposalResult
import com.aiopenpocket.agent.core.plan.template.PlanTemplate
import com.aiopenpocket.agent.core.plan.template.PlanTemplateRegistry
import com.aiopenpocket.agent.core.plan.validation.PlanValidator
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.loop.AgentMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanPlanner]/[com.aiopenpocket.agent.core.plan.planner.PlanControlReducer]/[PlanControlSchemas]
 * are pure-JVM-testable (a fake [LlmClient]/[AgentResolver]/[PlanTemplateRegistry] and a real
 * [PlanPromptBuilder] over a no-op [AssetSource] — no Robolectric, no Room), same posture as
 * [PlanValidatorTest]/[PlanStateMachineTest]/[PlanChangeClassifierTest]. Covers this task's own
 * required scenarios: schema isolation from real tools (Task 4 Step 6), one recognized control
 * call/malformed arguments/duplicate calls/text-only response/provider error (Task 4 Step 1), an
 * invalid proposal rejected via the frozen [PlanValidator], and a revision of an approved version
 * always landing on a NEW pending version.
 */
class PlanPlannerTest {

  // --- fixtures --------------------------------------------------------------------------------

  private fun agent(id: String = "agent-1", toolNames: Set<String> = setOf("memory_tool", "heartbeat")) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Test agent",
    description = "Agent used by PlanPlanner tests.",
    iconKey = "sparkles",
    systemPrompt = "You are a test agent.",
    toolNames = toolNames,
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
    allowedWidgetTemplates = emptySet(),
    hooks = emptyList(),
    configVersion = 1,
    status = AgentStatus.PUBLISHED,
  )

  private fun template(
    id: String = "template-1",
    agentId: String = "agent-1",
    supportedHorizons: Set<PlanHorizon> = setOf(PlanHorizon.SINGLE_RUN, PlanHorizon.PROGRAM),
    requiredConstraints: List<String> = emptyList(),
    forbiddenToolNames: Set<String> = emptySet(),
  ) = PlanTemplate(
    id = id,
    agentId = agentId,
    supportedHorizons = supportedHorizons,
    requiredConstraints = requiredConstraints,
    suggestedSteps = emptyList(),
    suggestedCriteria = emptyList(),
    forbiddenToolNames = forbiddenToolNames,
  )

  private class FakeLlmClient(private val events: List<StreamEvent>) : LlmClient {
    var lastMessages: List<ChatMessage>? = null
      private set
    var lastTools: List<FunctionSchema>? = null
      private set

    override fun streamChat(messages: List<ChatMessage>, tools: List<FunctionSchema>, provider: LlmProvider): Flow<StreamEvent> {
      lastMessages = messages
      lastTools = tools
      return flowOf(*events.toTypedArray())
    }
  }

  private class FakeAgentResolver(private val agents: Map<String, AgentDefinition>) : AgentResolver {
    override suspend fun resolve(id: String?): AgentDefinition? = id?.let { agents[it] }
    override fun observeRoster(): Flow<List<AgentDefinition>> = flowOf(agents.values.toList())
  }

  private class FakeTemplateRegistry(private val templates: Map<String, PlanTemplate>) : PlanTemplateRegistry {
    override suspend fun getForAgent(agentId: String?): PlanTemplate? = agentId?.let { templates[it] }
  }

  private class FakeTool(override val name: String) : Tool {
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  private fun planner(
    llm: LlmClient,
    agents: Map<String, AgentDefinition> = mapOf("agent-1" to agent()),
    templates: Map<String, PlanTemplate> = emptyMap(),
  ): PlanPlanner = PlanPlanner(
    llmClient = llm,
    agentResolver = FakeAgentResolver(agents),
    templateRegistry = FakeTemplateRegistry(templates),
    promptBuilder = PlanPromptBuilder(AssetSource { null }),
  )

  // --- JSON argument builders (mirror PlanControlSchemas' own field names exactly) --------------

  private fun stepJson(
    id: String,
    dependsOn: List<String> = emptyList(),
    expectedTools: List<String> = emptyList(),
    completionRuleType: String = "TOOL_SUCCEEDED",
  ): JsonObject = JsonObject(
    mapOf(
      "id" to JsonPrimitive(id),
      "title" to JsonPrimitive("Title $id"),
      "instruction" to JsonPrimitive("Do $id"),
      "dependsOn" to JsonArray(dependsOn.map { JsonPrimitive(it) }),
      "expectedTools" to JsonArray(expectedTools.map { JsonPrimitive(it) }),
      "completionRule" to JsonObject(mapOf("type" to JsonPrimitive(completionRuleType))),
    ),
  )

  private fun criterionJson(
    id: String,
    required: Boolean = true,
    verifierType: String = "TOOL_RESULT",
  ): JsonObject = JsonObject(
    mapOf(
      "id" to JsonPrimitive(id),
      "description" to JsonPrimitive("Criterion $id"),
      "required" to JsonPrimitive(required),
      "verifier" to JsonObject(
        mapOf(
          "type" to JsonPrimitive(verifierType),
          "config" to JsonObject(mapOf("toolName" to JsonPrimitive("memory_tool"))),
        ),
      ),
    ),
  )

  private fun submitPlanArgs(
    objective: String = "objective",
    steps: List<JsonObject> = listOf(stepJson("s1")),
    criteria: List<JsonObject> = listOf(criterionJson("c1")),
    constraints: List<String> = emptyList(),
  ): Map<String, JsonElement> = mapOf(
    "objective" to JsonPrimitive(objective),
    "constraints" to JsonArray(constraints.map { JsonPrimitive(it) }),
    "steps" to JsonArray(steps),
    "criteria" to JsonArray(criteria),
  )

  private fun textThenCall(text: String, callName: String, args: Map<String, JsonElement>) = listOf(
    StreamEvent.TextDelta(text),
    StreamEvent.ToolCall("call-1", callName, args),
    StreamEvent.Finish("tool_calls"),
  )

  // --- schema isolation (Task 4 Step 6) ----------------------------------------------------------

  @Test
  fun `planner control schema names equal PlanValidator's frozen control tool name set`() {
    assertEquals(PlanValidator.PLANNER_CONTROL_TOOL_NAMES, PlanControlSchemas.NAMES)
  }

  @Test
  fun `planner control schema names are disjoint from AgentValidator's known real tool names`() {
    assertTrue((PlanControlSchemas.NAMES intersect AgentValidator.KNOWN_TOOL_NAMES).isEmpty())
  }

  @Test
  fun `a ToolRegistry populated with every known real tool never exposes a planner control name`() {
    val registry = ToolRegistry(AgentValidator.KNOWN_TOOL_NAMES.map { FakeTool(it) }.toSet())
    val registeredNames = registry.getAllSchemas().map { it.function.name }.toSet()
    assertTrue((registeredNames intersect PlanControlSchemas.NAMES).isEmpty())
    assertTrue(registry.getSchemasFor(PlanControlSchemas.NAMES).isEmpty())
  }

  @Test
  fun `each control schema is a well-formed function object schema`() {
    for (schema in PlanControlSchemas.ALL) {
      assertEquals("function", schema.type)
      assertTrue("name blank for $schema", schema.function.name.isNotBlank())
      assertTrue("description blank for ${schema.function.name}", schema.function.description.isNotBlank())
      val params = schema.function.parameters
      assertEquals("object", (params["type"] as? JsonPrimitive)?.content)
      assertTrue(params["properties"] is JsonObject)
      assertTrue(params["required"] is JsonArray)
    }
  }

  // --- exactly one recognized control call / malformed / duplicate / text-only / provider error --

  @Test
  fun `submit_plan with valid content produces a Proposed result carrying the model's explanation text`() = runTest {
    val llm = FakeLlmClient(textThenCall("Here is my plan.", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val proposed = result as? PlanProposalResult.Proposed
    assertTrue("expected Proposed, got $result", proposed != null)
    assertEquals("plan-1", proposed!!.version.planId)
    assertEquals(1, proposed.version.version)
    assertNull(proposed.version.approvedAt)
    assertEquals("Here is my plan.", proposed.explanation)
    // The planner turn must never see a real action-tool schema, only the 3 control schemas.
    assertEquals(PlanControlSchemas.NAMES, llm.lastTools?.map { it.function.name }?.toSet())
  }

  @Test
  fun `request_plan_information produces NeedsInfo with the asked question`() = runTest {
    val llm = FakeLlmClient(
      listOf(
        StreamEvent.ToolCall("call-1", PlanControlSchemas.REQUEST_PLAN_INFORMATION, mapOf("question" to JsonPrimitive("What is your budget?"))),
        StreamEvent.Finish("tool_calls"),
      ),
    )

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "help me plan")

    assertEquals(PlanProposalResult.NeedsInfo("What is your budget?"), result)
  }

  @Test
  fun `a text-only response with no control call fails`() = runTest {
    val llm = FakeLlmClient(listOf(StreamEvent.TextDelta("Sure, here is a plan..."), StreamEvent.Finish("stop")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
  }

  @Test
  fun `two control calls in the same turn fail as duplicate calls`() = runTest {
    val llm = FakeLlmClient(
      listOf(
        StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()),
        StreamEvent.ToolCall("call-2", PlanControlSchemas.REQUEST_PLAN_INFORMATION, mapOf("question" to JsonPrimitive("huh?"))),
        StreamEvent.Finish("tool_calls"),
      ),
    )

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
  }

  @Test
  fun `submit_plan missing objective fails as malformed, not Invalid`() = runTest {
    val args = submitPlanArgs().minus("objective")
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
  }

  @Test
  fun `submit_plan with a non-array steps field fails as malformed`() = runTest {
    val args = submitPlanArgs() + mapOf("steps" to JsonPrimitive("not an array"))
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
  }

  @Test
  fun `a provider stream error fails the proposal`() = runTest {
    val llm = FakeLlmClient(listOf(StreamEvent.Error("upstream exploded", code = "500")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertEquals(PlanProposalResult.Failed("upstream exploded"), result)
  }

  // --- an invalid proposal is rejected via the frozen PlanValidator ------------------------------

  @Test
  fun `a dependency cycle is rejected via PlanValidator, not silently accepted`() = runTest {
    val steps = listOf(stepJson("a", dependsOn = listOf("b")), stepJson("b", dependsOn = listOf("a")))
    val args = submitPlanArgs(steps = steps)
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val invalid = result as? PlanProposalResult.Invalid
    assertTrue("expected Invalid, got $result", invalid != null)
    assertTrue(invalid!!.issues.any { it.code == "dependency_cycle" })
  }

  @Test
  fun `a step tool outside the agent's scope is rejected via PlanValidator`() = runTest {
    val args = submitPlanArgs(steps = listOf(stepJson("s1", expectedTools = listOf("nonexistent_tool"))))
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val invalid = result as? PlanProposalResult.Invalid
    assertTrue("expected Invalid, got $result", invalid != null)
    assertTrue(invalid!!.issues.any { it.code == "tool_outside_agent_scope" })
  }

  @Test
  fun `a present-but-blank objective reaches PlanValidator as Invalid, not Failed`() = runTest {
    val args = submitPlanArgs(objective = "   ")
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val invalid = result as? PlanProposalResult.Invalid
    assertTrue("a blank (but present) objective is PlanValidator's job, expected Invalid, got $result", invalid != null)
    assertTrue(invalid!!.issues.any { it.code == "blank_objective" })
  }

  @Test
  fun `a forbidden domain tool used by a step is rejected even though the agent otherwise allows it`() = runTest {
    val domainTemplate = template(forbiddenToolNames = setOf("sms"))
    val args = submitPlanArgs(steps = listOf(stepJson("s1", expectedTools = listOf("sms"))))
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(
      llm,
      agents = mapOf("agent-1" to agent(toolNames = setOf("sms", "memory_tool"))),
      templates = mapOf("agent-1" to domainTemplate),
    ).propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val invalid = result as? PlanProposalResult.Invalid
    assertTrue("expected Invalid, got $result", invalid != null)
    assertTrue(invalid!!.issues.any { it.code == "forbidden_tool_in_step" })
  }

  // --- required domain constraints are merged, not merely checked -------------------------------

  @Test
  fun `required domain constraints are merged into the proposal even if the model omits them`() = runTest {
    val domainTemplate = template(requiredConstraints = listOf("Никогда не давай финансовых рекомендаций"))
    val args = submitPlanArgs(constraints = emptyList())
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm, templates = mapOf("agent-1" to domainTemplate))
      .propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    val proposed = result as? PlanProposalResult.Proposed
    assertTrue("expected Proposed, got $result", proposed != null)
    assertTrue(proposed!!.version.constraints.contains("Никогда не давай финансовых рекомендаций"))
  }

  // --- editing an approved plan yields a NEW pending version, never mutating the approved one ----

  @Test
  fun `propose_plan_revision of an approved version yields a new higher-numbered pending version`() = runTest {
    val approved = PlanVersion(
      planId = "plan-1",
      version = 1,
      objective = "old objective",
      assumptions = emptyList(),
      constraints = emptyList(),
      steps = listOf(
        PlanStep(
          id = "s1",
          title = "Old step",
          instruction = "Old instruction",
          dependsOn = emptySet(),
          expectedTools = emptySet(),
          schedule = null,
          completionRule = CompletionRule(type = CompletionRuleType.TOOL_SUCCEEDED),
        ),
      ),
      criteria = listOf(
        PlanCriterion(
          id = "c1",
          description = "Old criterion",
          verifier = CriterionVerifier(type = VerifierType.MODEL_ASSESSMENT),
          required = false,
        ),
      ),
      createdBy = PlanAuthor.USER,
      approvedAt = 1_000L,
    )
    val args = submitPlanArgs(objective = "new objective")
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.PROPOSE_PLAN_REVISION, args), StreamEvent.Finish("tool_calls")))

    val result = planner(llm).propose(
      planId = "plan-1",
      agentId = "agent-1",
      horizon = PlanHorizon.SINGLE_RUN,
      userMessage = "please change the objective",
      existingVersion = approved,
    )

    val proposed = result as? PlanProposalResult.Proposed
    assertTrue("expected Proposed, got $result", proposed != null)
    assertEquals(2, proposed!!.version.version)
    assertNull("a freshly proposed revision must not already be approved", proposed.version.approvedAt)
    assertEquals("new objective", proposed.version.objective)
    // The original approved version object itself must be untouched.
    assertEquals(1, approved.version)
    assertEquals(1_000L, approved.approvedAt)
    assertEquals("old objective", approved.objective)
  }

  // --- guard rails: unresolvable agent / unsupported horizon short-circuit before calling the LLM -

  @Test
  fun `an unresolvable agent fails closed without ever calling the LLM`() = runTest {
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()), StreamEvent.Finish("tool_calls")))

    val result = planner(llm, agents = emptyMap()).propose(
      planId = "plan-1",
      agentId = "missing-agent",
      horizon = PlanHorizon.SINGLE_RUN,
      userMessage = "plan my week",
    )

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
    assertNull("the LLM must never be called when the agent can't be resolved", llm.lastMessages)
  }

  @Test
  fun `a horizon unsupported by the agent's template fails closed without ever calling the LLM`() = runTest {
    val domainTemplate = template(supportedHorizons = setOf(PlanHorizon.PROGRAM))
    val llm = FakeLlmClient(listOf(StreamEvent.ToolCall("call-1", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()), StreamEvent.Finish("tool_calls")))

    val result = planner(llm, templates = mapOf("agent-1" to domainTemplate)).propose(
      planId = "plan-1",
      agentId = "agent-1",
      horizon = PlanHorizon.SINGLE_RUN,
      userMessage = "plan my week",
    )

    assertTrue("expected Failed, got $result", result is PlanProposalResult.Failed)
    assertNull("the LLM must never be called when the horizon is unsupported", llm.lastMessages)
  }

  @Test
  fun `a missing template is a normal no-guidance outcome, not a failure`() = runTest {
    val llm = FakeLlmClient(textThenCall("plan text", PlanControlSchemas.SUBMIT_PLAN, submitPlanArgs()))

    val result = planner(llm, templates = emptyMap())
      .propose(planId = "plan-1", agentId = "agent-1", horizon = PlanHorizon.SINGLE_RUN, userMessage = "plan my week")

    assertTrue("no template must not block a valid proposal, got $result", result is PlanProposalResult.Proposed)
  }
}
