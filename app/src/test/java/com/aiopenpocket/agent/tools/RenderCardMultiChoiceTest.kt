package com.aiopenpocket.agent.tools

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.TurnContextHolder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `render_card` accepts a well-formed `multi_choice` and sends the model back a reason when it is not. */
class RenderCardMultiChoiceTest {

  private val tool = RenderCardTool(TurnContextHolder())

  private fun call(params: String): Map<String, JsonElement> = mapOf(
    "template" to JsonPrimitive("multi_choice"),
    "params" to Json.parseToJsonElement(params).jsonObject,
  )

  @Test
  fun `a question with three options becomes a card`() = runTest {
    val result = tool.execute(call("""{"question":"Q?","options":["a","b","c"],"preselected":[0]}"""))

    assertTrue(result is ToolResult.Success)
    assertTrue((result as ToolResult.Success).data.startsWith("""{"template":"multi_choice""""))
  }

  @Test
  fun `one option is a recoverable error, not a card`() = runTest {
    val result = tool.execute(call("""{"question":"Q?","options":["only"]}"""))

    assertTrue(result is ToolResult.Error && result.recoverable)
    assertTrue((result as ToolResult.Error).message.contains("2-4"))
  }

  @Test
  fun `more than four options is rejected - one question per card, short lists`() = runTest {
    val result = tool.execute(call("""{"question":"Q?","options":["a","b","c","d","e"]}"""))

    assertTrue(result is ToolResult.Error && result.recoverable)
    assertTrue((result as ToolResult.Error).message.contains("got 5"))
  }

  @Test
  fun `a card without a question is rejected`() = runTest {
    val result = tool.execute(call("""{"options":["a","b"]}"""))

    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `multi_choice is offered to agents and known to the validator`() {
    assertTrue("multi_choice" in GENERAL_CARD_TEMPLATES)
    assertTrue("multi_choice" in AgentValidator.KNOWN_WIDGET_TEMPLATES)
    val enum = tool.schema.function.parameters.toString()
    assertTrue(enum.contains("multi_choice"))
  }

  @Test
  fun `a bound agent cannot render the review card even if its allowlist names it`() = runTest {
    // Only the draft tool's finish result produces that card, so its agent id is always a real draft.
    val holder = TurnContextHolder()
    holder.set(
      "c1",
      MiniAgentCatalog.get("budget")!!.toDefinition().copy(allowedWidgetTemplates = setOf("agent_review")),
    )
    val boundTool = RenderCardTool(holder)

    val result = boundTool.execute(
      mapOf(
        "template" to JsonPrimitive("agent_review"),
        "params" to Json.parseToJsonElement("""{"agent_id":"my-agent-1"}"""),
      ),
    )

    assertTrue(result is ToolResult.Error)
    assertEquals(false, tool.schema.function.parameters.toString().contains("agent_review"))
    assertTrue("agent_review" !in AgentValidator.KNOWN_WIDGET_TEMPLATES)
  }
}
