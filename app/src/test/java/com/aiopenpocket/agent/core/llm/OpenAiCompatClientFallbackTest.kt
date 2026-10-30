package com.aiopenpocket.agent.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The [OpenAiCompatClient] fallback contract: which response counts as a reason to take the next model in
 * the chain.
 *
 * A live bug (2026-07-13): NIM returned HTTP 200 and closed the stream without sending any content,
 * tool call or reasoning (`textChars=0 toolCalls=0 finish=stop`). The client treated it as success and
 * ended the turn with nothing, although five live models were waiting further down the chain — the user
 * saw an empty message.
 *
 * Robolectric — for the sake of `android.util.Log` inside the client (in plain JUnit it throws not-mocked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientFallbackTest {

  /** A response exactly like the broken deepseek-v4-flash one: a role chunk, finish and nothing else. */
  private val emptySse = """
    data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  private val textSse = """
    data: {"choices":[{"delta":{"content":"Привет"}}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  private fun config(model: String) = ProviderConfig(
    name = LlmProvider.META,
    baseUrl = "https://nim.test/chat/completions",
    model = model,
    apiKeyResolver = { "test-key" },
  )

  /** Both models live on one endpoint, so the engine tells them apart by the `model` field in the request body. */
  private fun engineFor(bodyByModel: Map<String, String>, calls: MutableList<String>) = MockEngine { request ->
    val body = (request.body as TextContent).text
    val model = bodyByModel.keys.first { body.contains("\"model\":\"$it\"") }
    calls += model
    respond(
      content = bodyByModel.getValue(model),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )
  }

  private fun clientOf(models: List<String>, engine: MockEngine) = OpenAiCompatClient(
    fallbackChain = ProviderFallbackChain(mapOf(LlmProvider.META to models.map { config(it) })),
    engine = engine,
    usageRecorder = NoOpLlmUsageRecorder,
  )

  @Test
  fun `empty 200 response falls back to the next model`() = runTest {
    val calls = mutableListOf<String>()
    val client = clientOf(
      models = listOf("empty-model", "good-model"),
      engine = engineFor(mapOf("empty-model" to emptySse, "good-model" to textSse), calls),
    )

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    // An empty 200 is not an answer: the turn must reach the next model, not end with nothing.
    assertEquals(listOf("empty-model", "good-model"), calls)
    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("Привет", text)
    assertTrue("пустой 200 не должен всплывать ошибкой", events.none { it is StreamEvent.Error })
  }

  @Test
  fun `non-empty response stops the chain`() = runTest {
    val calls = mutableListOf<String>()
    val client = clientOf(
      models = listOf("good-model", "never-called"),
      engine = engineFor(mapOf("good-model" to textSse, "never-called" to textSse), calls),
    )

    client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    // Invariant: once a real answer arrives, we do not go further down the chain (or we would glue two answers into one).
    assertEquals(listOf("good-model"), calls)
  }

  @Test
  fun `HTTP 400 stops the chain with a request-specific message, not a network one`() = runTest {
    val calls = mutableListOf<String>()
    val engine = MockEngine { request ->
      val body = (request.body as TextContent).text
      calls += listOf("rejecting-model", "never-called").first { body.contains("\"model\":\"$it\"") }
      respond(
        content = """{"error":{"message":"context length exceeded"}}""",
        status = HttpStatusCode.BadRequest,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val client = clientOf(models = listOf("rejecting-model", "never-called"), engine = engine)

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    // The next model would get a refusal of the request itself too — no point running the chain.
    assertEquals(listOf("rejecting-model"), calls)
    val error = events.single() as StreamEvent.Error
    assertEquals("400", error.code)
    assertTrue("400 — не «нет сети»: ${error.message}", !error.message.contains("нет сети"))
  }

  @Test
  fun `a reply cut by max_tokens with no content is an error and does not walk the chain`() = runTest {
    val lengthSse = """
      data: {"choices":[{"delta":{"reasoning":"Думаю…"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"length"}]}
      data: [DONE]
    """.trimIndent()
    val calls = mutableListOf<String>()
    val client = clientOf(
      models = listOf("thinker", "never-called"),
      engine = engineFor(mapOf("thinker" to lengthSse, "never-called" to textSse), calls),
    )

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(listOf("thinker"), calls)
    assertEquals(CODE_LENGTH_EMPTY, (events.single() as StreamEvent.Error).code)
  }

  @Test
  fun `all models empty surfaces an error instead of silent emptiness`() = runTest {
    val calls = mutableListOf<String>()
    val client = clientOf(
      models = listOf("empty-1", "empty-2"),
      engine = engineFor(mapOf("empty-1" to emptySse, "empty-2" to emptySse), calls),
    )

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    // The chain is exhausted — the user must see an error, not a silent screen.
    assertEquals(listOf("empty-1", "empty-2"), calls)
    assertTrue("исчерпанная цепочка обязана дать Error", events.any { it is StreamEvent.Error })
  }
}
