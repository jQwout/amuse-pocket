package com.aiopenpocket.agent.core.llm

import com.aiopenpocket.agent.core.mcp.MalformedArguments
import com.aiopenpocket.agent.loop.AgentMode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.random.Random

/**
 * Three behaviours of the chat client taken from a review against the Meta Model API docs: the reasoning
 * effort on the wire, tool arguments that are not JSON, and backoff on transient errors.
 *
 * Robolectric — for `android.util.Log` inside the client, as in the neighbouring client tests. Time is
 * virtual (`runTest`), so a "wait 2 s" is asserted on `currentTime`, not slept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientMetaFixesTest {

  private val textSse = """
    data: {"choices":[{"delta":{"content":"ok"}}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  /** Jitter pinned to zero, so waits are exact: max(backoff, Retry-After) and nothing on top. */
  private val noJitter = object : Random() {
    override fun nextBits(bitCount: Int): Int = 0
  }

  private fun config(
    model: String,
    provider: LlmProvider = LlmProvider.META,
    reasoning: Boolean = false,
  ) = ProviderConfig(
    maxTokens = 16000,
    name = provider,
    baseUrl = "https://llm.test/chat/completions",
    model = model,
    reasoning = reasoning,
    apiKeyResolver = { "test-key" },
  )

  private fun clientOf(
    provider: LlmProvider,
    configs: List<ProviderConfig>,
    engine: MockEngine,
    usageRecorder: LlmUsageRecorder = NoOpLlmUsageRecorder,
  ) = OpenAiCompatClient(
    fallbackChain = ProviderFallbackChain(mapOf(provider to configs)),
    engine = engine,
    usageRecorder = usageRecorder,
  ).also { it.retryRandom = noJitter }

  // --- 1. reasoning effort ---------------------------------------------------------------------

  /** Runs one turn under [effort] and returns the request body the client sent. */
  private suspend fun bodyFor(
    provider: LlmProvider,
    reasoning: Boolean,
    effort: ReasoningEffort?,
  ): String {
    val bodies = mutableListOf<String>()
    val engine = MockEngine { request ->
      bodies += (request.body as TextContent).text
      respond(textSse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    val client = clientOf(provider, listOf(config("m", provider, reasoning)), engine)
    val run = suspend { client.streamChat(emptyList(), emptyList(), provider).toList() }
    if (effort == null) run() else withContext(ReasoningEffortContext(effort)) { run() }
    return bodies.single()
  }

  @Test
  fun `Meta route sends top-level reasoning_effort to a reasoning model`() = runTest {
    val body = bodyFor(LlmProvider.META, reasoning = true, effort = ReasoningEffort.MEDIUM)
    assertTrue(body, body.contains("\"reasoning_effort\":\"medium\""))
    assertTrue("Meta has no nested reasoning object: $body", !body.contains("\"reasoning\":"))
  }

  @Test
  fun `a 400 to the effort field is retried once without it, and the field stays off for that model`() = runTest {
    val bodies = mutableListOf<String>()
    val engine = MockEngine { request ->
      val body = (request.body as TextContent).text
      bodies += body
      if (body.contains("reasoning_effort")) {
        respond("""{"error":{"message":"unknown field"}}""", HttpStatusCode.BadRequest)
      } else {
        respond(textSse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
      }
    }
    val client = clientOf(LlmProvider.META, listOf(config("m", LlmProvider.META, reasoning = true)), engine)
    val turn = suspend {
      withContext(ReasoningEffortContext(ReasoningEffort.LOW)) {
        client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()
      }
    }

    val first = turn()
    assertTrue("the turn must not fail on the field: $first", first.none { it is StreamEvent.Error })
    assertEquals(2, bodies.size)
    assertTrue(!bodies[1].contains("reasoning_effort"))

    turn()
    assertEquals("the next turn goes straight without the field", 3, bodies.size)
    assertTrue(!bodies[2].contains("reasoning_effort"))
  }

  @Test
  fun `a 400 that is not about the effort field still stops the chain and keeps the field on`() = runTest {
    val bodies = mutableListOf<String>()
    val engine = MockEngine { request ->
      bodies += (request.body as TextContent).text
      respond("""{"error":{"message":"context too long"}}""", HttpStatusCode.BadRequest)
    }
    val client = clientOf(LlmProvider.META, listOf(config("m", LlmProvider.META, reasoning = true)), engine)
    val turn = suspend {
      withContext(ReasoningEffortContext(ReasoningEffort.LOW)) {
        client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()
      }
    }

    assertTrue(turn().any { it is StreamEvent.Error })
    assertEquals("one try with the field, one without, then stop", 2, bodies.size)
    turn()
    assertTrue("a request-level 400 must not switch the effort off for good", bodies[2].contains("reasoning_effort"))
  }

  @Test
  fun `the Meta route sends the top-level reasoning_effort field, not a nested object`() = runTest {
    val body = bodyFor(LlmProvider.META, reasoning = true, effort = ReasoningEffort.HIGH)
    assertTrue(body, body.contains("\"reasoning_effort\":\"high\""))
    assertTrue("Meta documents the top-level field only: $body", !body.contains("\"reasoning\":{"))
  }

  @Test
  fun `a model that is not a reasoning model gets no effort on either route`() = runTest {
    for (provider in LlmProvider.entries) {
      val body = bodyFor(provider, reasoning = false, effort = ReasoningEffort.HIGH)
      assertTrue("$provider: $body", !body.contains("reasoning"))
    }
  }

  @Test
  fun `a call outside any mode sends no effort and keeps the model's own default`() = runTest {
    val body = bodyFor(LlmProvider.META, reasoning = true, effort = null)
    assertTrue(body, !body.contains("reasoning"))
  }

  @Test
  fun `max_tokens stays at the catalog value when effort is sent`() = runTest {
    val body = bodyFor(LlmProvider.META, reasoning = true, effort = ReasoningEffort.LOW)
    assertTrue(body, body.contains("\"max_tokens\":16000"))
  }

  @Test
  fun `modes map to lesson low, chat medium, planning and critique high`() {
    assertEquals(ReasoningEffort.LOW, reasoningEffortFor(AgentMode.TUTOR))
    assertEquals(ReasoningEffort.MEDIUM, reasoningEffortFor(AgentMode.CHAT))
    assertEquals(ReasoningEffort.HIGH, reasoningEffortFor(AgentMode.PLAN))
    assertEquals(ReasoningEffort.HIGH, reasoningEffortFor(AgentMode.REFLECT))
  }

  // --- 2. malformed tool arguments -------------------------------------------------------------

  private fun toolCallSse(arguments: String) = """
    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"act","arguments":"$arguments"}}]}}]}
    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
    data: [DONE]
  """.trimIndent()

  private suspend fun toolCallsFor(argumentsJsonEscaped: String): List<StreamEvent.ToolCall> {
    val engine = MockEngine {
      respond(toolCallSse(argumentsJsonEscaped), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    val client = clientOf(LlmProvider.META, listOf(config("m", LlmProvider.META)), engine)
    return client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList().filterIsInstance<StreamEvent.ToolCall>()
  }

  @Test
  fun `arguments that are not JSON reach the executor as a marker with an excerpt, not an empty map`() = runTest {
    // The model wrote {"title": "Buy milk   — the closing brace and quote are missing.
    val call = toolCallsFor("{\\\"title\\\": \\\"Buy milk").single()
    val excerpt = MalformedArguments.excerptOf(call.arguments)
    assertEquals("{\"title\": \"Buy milk", excerpt)
  }

  @Test
  fun `empty arguments of a no-parameter tool stay an empty map`() = runTest {
    // Many models send "" instead of {} for a tool with no parameters — that is not an error.
    val call = toolCallsFor("").single()
    assertTrue(call.arguments.isEmpty())
  }

  @Test
  fun `valid arguments pass through untouched`() = runTest {
    val call = toolCallsFor("{\\\"a\\\":\\\"b\\\"}").single()
    assertEquals(mapOf("a" to JsonPrimitive("b")), call.arguments)
    assertNull(MalformedArguments.excerptOf(call.arguments))
  }

  // --- 3. backoff on transient errors ----------------------------------------------------------

  /** Answers each request with the next entry of [script] (a status, or null for a good stream). */
  private class Script(val calls: MutableList<String>, private val script: List<Pair<Int, String?>>) {
    private var i = 0
    fun next(model: String): Pair<Int, String?> {
      calls += model
      return script[minOf(i++, script.size - 1)]
    }
  }

  private fun engineOf(calls: MutableList<String>, perModel: Map<String, List<Pair<Int, String?>>>): MockEngine {
    val scripts = perModel.mapValues { Script(calls, it.value) }
    return MockEngine { request ->
      val body = (request.body as TextContent).text
      val model = scripts.keys.first { body.contains("\"model\":\"$it\"") }
      val (status, retryAfter) = scripts.getValue(model).next(model)
      if (status == 200) {
        respond(textSse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
      } else {
        val headers = if (retryAfter != null) {
          headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(retryAfter))
        } else {
          headersOf(HttpHeaders.ContentType, "application/json")
        }
        respond("""{"error":{"message":"x"}}""", HttpStatusCode.fromValue(status), headers)
      }
    }
  }

  private fun twoModels(engine: MockEngine) = clientOf(
    LlmProvider.META,
    listOf(config("a", LlmProvider.META), config("b", LlmProvider.META)),
    engine,
  )

  @Test
  fun `429 with Retry-After 2 retries the same model after 2 seconds`() = runTest {
    val calls = mutableListOf<String>()
    val client = twoModels(engineOf(calls, mapOf("a" to listOf(429 to "2", 200 to null), "b" to listOf(200 to null))))

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals("the SAME model, twice — not the next one", listOf("a", "a"), calls)
    assertEquals("virtual time waited exactly Retry-After", 2_000L, testScheduler.currentTime)
    assertEquals("ok", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content })
    assertTrue(events.none { it is StreamEvent.Error })
  }

  @Test
  fun `503 backs off 1 s then 2 s on the same model before moving to the next`() = runTest {
    val calls = mutableListOf<String>()
    val client = twoModels(engineOf(calls, mapOf("a" to listOf(503 to null), "b" to listOf(200 to null))))

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(listOf("a", "a", "a", "b"), calls)
    assertEquals(1_000L + 2_000L, testScheduler.currentTime)
    assertTrue(events.none { it is StreamEvent.Error })
  }

  @Test
  fun `Retry-After longer than the turn can afford skips the wait and falls to the next model`() = runTest {
    val calls = mutableListOf<String>()
    val client = twoModels(engineOf(calls, mapOf("a" to listOf(503 to "60"), "b" to listOf(200 to null))))

    client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(listOf("a", "b"), calls)
    assertEquals("no waiting at all", 0L, testScheduler.currentTime)
  }

  @Test
  fun `the total backoff of one turn is capped across models`() = runTest {
    val calls = mutableListOf<String>()
    // Every model is overloaded, each asking for 8 s: 8 s fits, the second 8 s fits (16 s), the third would be 24 s.
    val client = OpenAiCompatClient(
      fallbackChain = ProviderFallbackChain(
        mapOf(LlmProvider.META to listOf("a", "b", "c").map { config(it, LlmProvider.META) }),
      ),
      engine = engineOf(calls, mapOf("a" to listOf(503 to "8"), "b" to listOf(503 to "8"), "c" to listOf(503 to "8"))),
      usageRecorder = NoOpLlmUsageRecorder,
    ).also { it.retryRandom = noJitter }

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertTrue("a turn must not sleep past its cap: ${testScheduler.currentTime}", testScheduler.currentTime <= 20_000L)
    assertEquals("503", (events.last() as StreamEvent.Error).code)
  }

  @Test
  fun `402 is not retried and not walked down the chain`() = runTest {
    val calls = mutableListOf<String>()
    val client = twoModels(engineOf(calls, mapOf("a" to listOf(402 to null), "b" to listOf(200 to null))))

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(listOf("a"), calls)
    assertEquals(0L, testScheduler.currentTime)
    assertEquals("402", (events.single() as StreamEvent.Error).code)
  }

  @Test
  fun `504 is not a plain retry of the same model`() = runTest {
    val calls = mutableListOf<String>()
    val client = twoModels(engineOf(calls, mapOf("a" to listOf(504 to null), "b" to listOf(200 to null))))

    client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(listOf("a", "b"), calls)
    assertEquals(0L, testScheduler.currentTime)
  }

  @Test
  fun `a stream that broke after the answer started is neither retried nor handed to the next model`() = runTest {
    val calls = mutableListOf<String>()
    val engine = engineOf(calls, mapOf("a" to listOf(200 to null), "b" to listOf(200 to null)))
    // The failure happens after the text was emitted (the spend is recorded once the stream is read).
    val breaking = object : LlmUsageRecorder {
      override suspend fun record(usage: LlmCallUsage) = throw IOException("connection dropped")
    }
    val client = clientOf(
      LlmProvider.META,
      listOf(config("a", LlmProvider.META), config("b", LlmProvider.META)),
      engine,
      breaking,
    )

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals("one request: the half-answer is not duplicated by a retry or a fallback", listOf("a"), calls)
    assertEquals(0L, testScheduler.currentTime)
    assertTrue(events.first() is StreamEvent.TextDelta)
    assertTrue(events.last() is StreamEvent.Error)
  }
}
