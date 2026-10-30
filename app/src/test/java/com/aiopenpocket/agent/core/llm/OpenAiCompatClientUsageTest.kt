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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spending accounting: what the client ASKS the provider for and what it records as the result of a call.
 *
 * These numbers are why accounting was set up in the first place — pricing is calculated from them — so
 * what is checked is not "the method was called" but the content of the record: the provider's measurement
 * must arrive verbatim, and its silence must become an honestly labeled estimate, not a quiet zero.
 *
 * Robolectric — because of `android.util.Log` inside the client, as in the neighboring client tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientUsageTest {

  private class RecordingUsageRecorder : LlmUsageRecorder {
    val recorded = mutableListOf<LlmCallUsage>()
    override suspend fun record(usage: LlmCallUsage) {
      recorded += usage
    }
  }

  private val withUsageSse = """
    data: {"choices":[{"delta":{"content":"Привет"}}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: {"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":48,"cost":0.00031}}
    data: [DONE]
  """.trimIndent()

  private val withoutUsageSse = """
    data: {"choices":[{"delta":{"content":"Привет"}}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  /** HTTP 200, but neither text nor a tool call — a degraded model. The prompt has been paid for, though. */
  private val emptySse = """
    data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}
    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
    data: [DONE]
  """.trimIndent()

  private fun config(provider: LlmProvider) = ProviderConfig(
    name = provider,
    baseUrl = "https://provider.test/chat/completions",
    model = "vendor/test-model",
    apiKeyResolver = { "test-key" },
  )

  private fun clientOf(
    provider: LlmProvider,
    sse: String,
    recorder: LlmUsageRecorder,
    sentBodies: MutableList<String> = mutableListOf(),
  ) = OpenAiCompatClient(
    fallbackChain = ProviderFallbackChain(mapOf(provider to listOf(config(provider)))),
    usageRecorder = recorder,
    engine = MockEngine { request ->
      sentBodies += (request.body as TextContent).text
      respond(
        content = sse,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
      )
    },
  )

  private val message = listOf(ChatMessage(role = "user", content = "привет"))

  @Test
  fun `usage named by the provider is recorded verbatim, not estimated`() = runTest {
    val recorder = RecordingUsageRecorder()
    clientOf(LlmProvider.META, withUsageSse, recorder)
      .streamChat(message, emptyList(), LlmProvider.META).toList()

    val usage = recorder.recorded.single()
    assertEquals(1200, usage.promptTokens)
    assertEquals(48, usage.completionTokens)
    assertEquals(0.00031, usage.costUsd ?: 0.0, 1e-9)
    assertFalse("замер провайдера не должен помечаться оценкой", usage.estimated)
    assertEquals("vendor/test-model", usage.model)
    assertEquals(LlmProvider.META, usage.provider)
  }

  @Test
  fun `silence from the provider becomes an estimate, explicitly flagged`() = runTest {
    val recorder = RecordingUsageRecorder()
    clientOf(LlmProvider.META, withoutUsageSse, recorder)
      .streamChat(message, emptyList(), LlmProvider.META).toList()

    val usage = recorder.recorded.single()
    assertTrue("оценка обязана быть помечена оценкой", usage.estimated)
    // Zero would mean "the turn was free" — the estimate must give a positive number.
    assertTrue("промпт оценивается по телу запроса", usage.promptTokens > 0)
    assertTrue("ответ оценивается по длине дельт", usage.completionTokens > 0)
    assertNull("цену никто не называл — придумывать её нельзя", usage.costUsd)
  }

  /**
   * An empty answer sends the chain to the next model, but the prompt has already gone out and been paid
   * for. Skipping such attempts would hide the most galling spending item: money for silence.
   */
  @Test
  fun `an empty 200 attempt is still recorded`() = runTest {
    val recorder = RecordingUsageRecorder()
    clientOf(LlmProvider.META, emptySse, recorder)
      .streamChat(message, emptyList(), LlmProvider.META).toList()

    assertEquals(1, recorder.recorded.size)
    assertTrue(recorder.recorded.single().promptTokens > 0)
  }

  @Test
  fun `the request asks for usage through the standard stream_options field`() = runTest {
    val bodies = mutableListOf<String>()
    clientOf(LlmProvider.META, withoutUsageSse, RecordingUsageRecorder(), bodies)
      .streamChat(message, emptyList(), LlmProvider.META).toList()

    assertTrue(
      "без stream_options.include_usage провайдер не пришлёт расход вовсе: ${bodies.single()}",
      bodies.single().contains("\"stream_options\":{\"include_usage\":true}"),
    )
  }

  /**
   * `usage: {"include": true}` was an extension of a gateway that is gone from the project. Meta answers 400
   * to fields it does not know, so the field must not be sent: the price comes from Meta's list instead.
   */
  @Test
  fun `the request carries no top-level usage field`() = runTest {
    val bodies = mutableListOf<String>()
    clientOf(LlmProvider.META, withUsageSse, RecordingUsageRecorder(), bodies)
      .streamChat(message, emptyList(), LlmProvider.META).toList()
    assertFalse(bodies.single(), bodies.single().contains("\"usage\":"))
  }
}
