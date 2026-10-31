package com.aiopenpocket.agent.tools

// @trace web-search-tool-c73ed8
// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.hook.HookDecision
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import com.aiopenpocket.agent.core.llm.WebSearchAnswer
import com.aiopenpocket.agent.core.llm.WebSearchClient
import com.aiopenpocket.agent.core.llm.WebSource
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ExecutionPolicy
import com.aiopenpocket.agent.core.policy.PolicyDecision
import com.aiopenpocket.agent.workers.HeartbeatRunner
import com.aiopenpocket.ui.agents.builder.components.ToolCatalog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `web_search` tool as the agent loop meets it: its arguments, what the model reads back, how its
 * result is bounded, and the policy facts the task rests on (READ risk, fenced as untrusted, in the
 * builder, allowed in the background). The network is a fake engine; the answers are written from Meta's
 * documentation, not recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebSearchToolTest {

  private val answerBody = """
    {"status":"completed","output":[{"type":"web_search_call","status":"completed"},
      {"type":"message","content":[{"type":"output_text","text":"Muse Spark came out on April 8, 2026.",
        "annotations":[
          {"type":"url_citation","url":"https://a.example/x","title":"A page","start_index":0,"end_index":10},
          {"type":"url_citation","url":"https://b.example/y","title":"B page","start_index":0,"end_index":10},
          {"type":"url_citation","url":"https://a.example/x","title":"A page","start_index":11,"end_index":20}]}]}],
     "usage":{"input_tokens":100,"output_tokens":50}}
  """.trimIndent()

  private fun toolOver(engine: MockEngine) = WebSearchTool(WebSearchClient(engine, { "sk-test" }, NoOpLlmUsageRecorder))

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> = pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun answering(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

  @Test
  fun `returns the answer, a numbered source list and the citing instruction`() = runTest {
    val result = toolOver(answering(answerBody)).execute(args("query" to "when was Muse Spark released"))

    val text = (result as ToolResult.Success).data
    assertTrue(text, text.startsWith("Muse Spark came out on April 8, 2026."))
    assertTrue(text, text.contains("[1] A page - https://a.example/x"))
    assertTrue(text, text.contains("[2] B page - https://b.example/y"))
    // The repeated citation is one source, not three.
    assertFalse(text, text.contains("[3]"))
    assertTrue(text, text.contains("markdown links"))
  }

  @Test
  fun `says plainly when no sources were cited`() = runTest {
    val body = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Paris."}]}]}"""

    val text = (toolOver(answering(body)).execute(args("query" to "capital of France")) as ToolResult.Success).data

    assertTrue(text, text.startsWith("Paris."))
    assertTrue(text, text.contains("No sources were cited"))
    assertFalse(text, text.contains("Sources:"))
  }

  @Test
  fun `the depth argument reaches the request`() = runTest {
    var body = ""
    val engine = MockEngine { request ->
      body = (request.body as TextContent).text
      respond(answerBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    toolOver(engine).execute(args("query" to "q", "depth" to "quick"))

    val tool = Json.parseToJsonElement(body).jsonObject.getValue("tools").jsonArray[0].jsonObject
    assertEquals("low", tool.getValue("search_context_size").jsonPrimitive.content)
  }

  @Test
  fun `a failure is a tool error the model can read, not an exception`() = runTest {
    val result = toolOver(answering("{}", HttpStatusCode.ServiceUnavailable)).execute(args("query" to "q"))

    assertTrue("expected Error, got $result", result is ToolResult.Error)
    assertTrue(result.toString(), result.toString().contains("503"))
  }

  @Test
  fun `a missing query is reported and nothing is sent`() = runTest {
    var requests = 0
    val engine = MockEngine {
      requests++
      respond(answerBody, HttpStatusCode.OK)
    }

    val result = toolOver(engine).execute(emptyMap())

    assertTrue(result is ToolResult.Error)
    assertEquals(0, requests)
  }

  @Test
  fun `the result is bounded to about four thousand characters`() {
    val long = WebSearchAnswer(
      text = "word ".repeat(5_000),
      sources = (1..20).map { WebSource("Title $it", "https://site$it.example/page") },
      searches = 1,
    )

    val text = WebSearchTool.formatForModel(long)

    assertTrue("too long: ${text.length}", text.length <= WebSearchTool.MAX_RESULT_CHARS)
    // The answer is what gives way; the cap on sources is eight, and the citing hint survives.
    assertTrue(text.contains("[8] Title 8"))
    assertFalse(text.contains("[9]"))
    assertTrue(text.endsWith("e.g. [title](url)."))
    assertTrue(text.contains("…"))
  }

  @Test
  fun `a source list too long for the budget loses its last entries before the answer is squeezed to nothing`() {
    val sources = (1..8).map { WebSource("T$it", "https://site$it.example/" + "p".repeat(440)) }

    val text = WebSearchTool.formatForModel(WebSearchAnswer("a".repeat(3_000), sources, 1))

    assertTrue("too long: ${text.length}", text.length <= WebSearchTool.MAX_RESULT_CHARS)
    // The answer keeps at least its first 500 characters; it is the sources that were trimmed to make room.
    assertTrue(text.startsWith("a".repeat(500)))
    assertTrue(text.contains("[1] T1"))
    assertFalse(text.contains("[8] T8"))
  }

  @Test
  fun `the schema asks for a query and offers three depths`() {
    val schema = toolOver(answering(answerBody)).schema.function
    val parameters = schema.parameters

    assertEquals("web_search", schema.name)
    val required = (parameters["required"] as JsonArray).map { it.jsonPrimitive.content }
    assertEquals(listOf("query"), required)
    val depth = parameters.getValue("properties").jsonObject.getValue("depth").jsonObject
    assertEquals(listOf("quick", "normal", "deep"), depth.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    // The model is told that it costs money.
    assertTrue(schema.description.contains("costs money"))
  }

  @Test
  fun `it is a read tool, so the policy lets it run in chat and in a builder preview`() {
    val tool = toolOver(answering(answerBody))
    val risk = tool.riskFor(args("query" to "q"))

    assertEquals(RiskClass.READ, risk)
    for (mode in ExecutionMode.entries) {
      val decision = ExecutionPolicy.evaluate(
        mode, risk, HookDecision(), "web_search", args("query" to "q"), "run", "req", "conv",
      )
      assertEquals(PolicyDecision.Execute, decision)
    }
  }

  @Test
  fun `web pages are untrusted content and the call gets a longer ceiling than a local tool`() {
    val tool = toolOver(answering(answerBody))

    assertTrue(tool.returnsUntrustedContent)
    assertTrue(tool.timeoutMs > ToolExecutor.TOOL_TIMEOUT_MS)
    assertTrue(tool.timeoutMs > WebSearchClient.REQUEST_TIMEOUT_MS)
  }

  @Test
  fun `the tool is known to the builder and available to a background agent that lists it`() {
    assertTrue("web_search" in AgentValidator.KNOWN_TOOL_NAMES)
    assertTrue(ToolCatalog.default.any { it.name == "web_search" && it.riskClass == RiskClass.READ && !it.requiresConsent })
    assertTrue("web_search" in HeartbeatRunner.SAFE_TOOL_NAMES)
  }

  // runBlocking, not runTest: the executor's timeout counts virtual time under runTest, and the fake
  // network answers on another thread - the test dispatcher would skip ahead and "time out" an instant
  // call (see the note on ToolExecutor.TOOL_TIMEOUT_MS).
  @Test
  fun `the registry hands the tool to the executor under its name`() = runBlocking {
    val registry = ToolRegistry(setOf(toolOver(answering(answerBody))))

    val result = ToolExecutor(registry).execute("web_search", args("query" to "q"))

    assertTrue(result.toString(), result is ToolResult.Success)
  }
}
