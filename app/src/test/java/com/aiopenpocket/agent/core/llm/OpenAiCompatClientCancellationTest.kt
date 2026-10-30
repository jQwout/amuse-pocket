package com.aiopenpocket.agent.core.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cancelling a turn is not "the provider failed". In Kotlin `CancellationException` extends `Exception`,
 * so the general `catch (e: Exception)` around a provider attempt catches it too: pressing Stop (or a new
 * turn that kills the previous one through `TurnManager.startTurn`) turned into "this model did not answer,
 * take the next one" and ran THE REST OF THE CHAIN, ending everything with
 * `StreamEvent.Error("All providers failed")` — from an already cancelled flow.
 *
 * The reference handling is right next door, in [com.aiopenpocket.agent.core.mcp.ToolExecutor]: there
 * `CancellationException` is caught FIRST and rethrown. Here we pin the same contract for the transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientCancellationTest {

  private val textSse = """
    data: {"choices":[{"delta":{"content":"Привет"}}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  /**
   * The key access counter = the counter of provider ATTEMPTS: `apiKeyResolver()` is the first thing a loop
   * iteration does, and the only part of it that does not suspend (and so runs even in an already cancelled
   * coroutine, where the suspend call after it throws at once). That is exactly why it shows whether the
   * loop reached the next models after cancellation.
   */
  private fun config(model: String, attempts: AtomicInteger) = ProviderConfig(
    name = LlmProvider.META,
    baseUrl = "https://nim.test/chat/completions",
    model = model,
    apiKeyResolver = {
      attempts.incrementAndGet()
      "test-key"
    },
  )

  private fun clientOf(models: List<String>, attempts: AtomicInteger, engine: MockEngine) =
    OpenAiCompatClient(
      fallbackChain = ProviderFallbackChain(mapOf(LlmProvider.META to models.map { config(it, attempts) })),
      engine = engine,
      usageRecorder = NoOpLlmUsageRecorder,
    )

  @Test
  fun `cancelling the collector aborts the run instead of walking the rest of the chain`() = runTest {
    val attempts = AtomicInteger(0)
    val firstCallEntered = CompletableDeferred<Unit>()
    // The first model "hangs" on a live connection until it is cancelled — the state in which the user
    // presses Stop. We wait on a NEVER-completing Deferred, not delay(): runTest's virtual time would skip delay.
    val neverCompletes = CompletableDeferred<Unit>()
    val engine = MockEngine {
      firstCallEntered.complete(Unit)
      neverCompletes.await()
      respond(content = textSse, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    val client = clientOf(listOf("hanging-model", "second-model", "third-model"), attempts, engine)

    val job = launch {
      client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()
    }
    firstCallEntered.await()
    job.cancelAndJoin()

    // Exactly one attempt: cancellation must take the loop out, not be counted as a model's failure.
    assertEquals(1, attempts.get())
  }

  @Test
  fun `cancellation propagates to the caller instead of becoming a StreamEvent Error`() = runTest {
    val attempts = AtomicInteger(0)
    val engine = MockEngine { throw CancellationException("stream cancelled") }
    val client = clientOf(listOf("cancelled-model", "second-model"), attempts, engine)

    // Caught by hand, not with assertThrows: the cancellation comes from the engine, and the test job itself
    // is not cancelled, so catching it here is safe and does not break the runTest scope.
    val thrown = try {
      client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()
      null
    } catch (e: Throwable) {
      e
    }

    assertTrue(
      "отмена обязана дойти до вызывающего, а не превратиться в событие: $thrown",
      thrown is CancellationException,
    )
    // And, of course, it must not be counted as "the model did not answer".
    assertEquals(1, attempts.get())
  }

  @Test
  fun `an ordinary provider failure still falls back - cancellation handling must not break that`() = runTest {
    val attempts = AtomicInteger(0)
    val engine = MockEngine { request ->
      val body = request.body.toString()
      if (body.contains("\"model\":\"broken-model\"")) {
        // 502, not 500: a 500 is now retried on the SAME model after a backoff (OpenAiCompatClientMetaFixesTest
        // covers that). This test is about a failure that goes straight to the next model.
        respond(content = "boom", status = HttpStatusCode.BadGateway)
      } else {
        respond(content = textSse, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
      }
    }
    val client = clientOf(listOf("broken-model", "good-model"), attempts, engine)

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.META).toList()

    assertEquals(2, attempts.get())
    assertEquals("Привет", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content })
  }
}
