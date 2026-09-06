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
 * Учёт расхода: что клиент СПРАШИВАЕТ у провайдера и что кладёт в учёт по итогу вызова.
 *
 * Ради этих чисел учёт и заводился — по ним считаются тарифы, — поэтому проверяется не «метод
 * вызвался», а содержимое записи: замер провайдера должен доезжать дословно, а его молчание —
 * превращаться в честно помеченную оценку, а не в тихий ноль.
 *
 * Robolectric — из-за `android.util.Log` внутри клиента, как и в соседних тестах клиента.
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

  /** HTTP 200, но ни текста, ни тул-вызова — деградировавшая модель. Промпт при этом оплачен. */
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
    clientOf(LlmProvider.OPENROUTER, withUsageSse, recorder)
      .streamChat(message, emptyList(), LlmProvider.OPENROUTER).toList()

    val usage = recorder.recorded.single()
    assertEquals(1200, usage.promptTokens)
    assertEquals(48, usage.completionTokens)
    assertEquals(0.00031, usage.costUsd ?: 0.0, 1e-9)
    assertFalse("замер провайдера не должен помечаться оценкой", usage.estimated)
    assertEquals("vendor/test-model", usage.model)
    assertEquals(LlmProvider.OPENROUTER, usage.provider)
  }

  @Test
  fun `silence from the provider becomes an estimate, explicitly flagged`() = runTest {
    val recorder = RecordingUsageRecorder()
    clientOf(LlmProvider.NIM, withoutUsageSse, recorder)
      .streamChat(message, emptyList(), LlmProvider.NIM).toList()

    val usage = recorder.recorded.single()
    assertTrue("оценка обязана быть помечена оценкой", usage.estimated)
    // Ноль означал бы «ход был бесплатным» — оценка обязана дать положительное число.
    assertTrue("промпт оценивается по телу запроса", usage.promptTokens > 0)
    assertTrue("ответ оценивается по длине дельт", usage.completionTokens > 0)
    assertNull("цену никто не называл — придумывать её нельзя", usage.costUsd)
  }

  /**
   * Пустой ответ уводит цепочку к следующей модели, но промпт уже улетел и оплачен. Пропускать такие
   * попытки — прятать самую обидную статью расхода: деньги за молчание.
   */
  @Test
  fun `an empty 200 attempt is still recorded`() = runTest {
    val recorder = RecordingUsageRecorder()
    clientOf(LlmProvider.NIM, emptySse, recorder)
      .streamChat(message, emptyList(), LlmProvider.NIM).toList()

    assertEquals(1, recorder.recorded.size)
    assertTrue(recorder.recorded.single().promptTokens > 0)
  }

  @Test
  fun `the request asks for usage through the standard stream_options field`() = runTest {
    val bodies = mutableListOf<String>()
    clientOf(LlmProvider.NIM, withoutUsageSse, RecordingUsageRecorder(), bodies)
      .streamChat(message, emptyList(), LlmProvider.NIM).toList()

    assertTrue(
      "без stream_options.include_usage провайдер не пришлёт расход вовсе: ${bodies.single()}",
      bodies.single().contains("\"stream_options\":{\"include_usage\":true}"),
    )
  }

  /**
   * `usage: {"include": true}` — расширение OpenRouter, а не стандарт. Строгий провайдер отвечает на
   * неизвестное поле HTTP 400 (так уже было с `type` у тулов), и цепочка молча ушла бы в фолбэк.
   */
  @Test
  fun `the OpenRouter-only usage field is not sent to NIM`() = runTest {
    val nimBodies = mutableListOf<String>()
    clientOf(LlmProvider.NIM, withoutUsageSse, RecordingUsageRecorder(), nimBodies)
      .streamChat(message, emptyList(), LlmProvider.NIM).toList()
    assertFalse(nimBodies.single().contains("\"usage\""))

    val orBodies = mutableListOf<String>()
    clientOf(LlmProvider.OPENROUTER, withUsageSse, RecordingUsageRecorder(), orBodies)
      .streamChat(message, emptyList(), LlmProvider.OPENROUTER).toList()
    assertTrue(orBodies.single().contains("\"usage\":{\"include\":true}"))
  }
}
