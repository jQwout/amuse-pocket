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
 * Контракт фолбэка [OpenAiCompatClient]: какой ответ считается поводом взять следующую модель цепочки.
 *
 * Живой баг (2026-07-13): NIM отдал HTTP 200 и закрыл стрим, не прислав ни content, ни tool-call, ни
 * reasoning (`textChars=0 toolCalls=0 finish=stop`). Клиент считал это успехом и завершал ход пустотой,
 * хотя ниже по цепочке ждали пять живых моделей — пользователь видел пустое сообщение.
 *
 * Robolectric — ради `android.util.Log` внутри клиента (в чистом JUnit он бросает not-mocked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiCompatClientFallbackTest {

  /** Ответ ровно как у сломавшегося deepseek-v4-flash: role-чанк, finish и ничего больше. */
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
    name = LlmProvider.NIM,
    baseUrl = "https://nim.test/chat/completions",
    model = model,
    apiKeyResolver = { "test-key" },
  )

  /** Обе модели живут на одном endpoint, поэтому движок различает их по полю `model` в теле запроса. */
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
    fallbackChain = ProviderFallbackChain(mapOf(LlmProvider.NIM to models.map { config(it) })),
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

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.NIM).toList()

    // Пустой 200 — не ответ: ход обязан доехать до следующей модели, а не закончиться пустотой.
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

    client.streamChat(emptyList(), emptyList(), LlmProvider.NIM).toList()

    // Инвариант: получив реальный ответ, дальше по цепочке не идём (иначе склеим два ответа в один).
    assertEquals(listOf("good-model"), calls)
  }

  @Test
  fun `all models empty surfaces an error instead of silent emptiness`() = runTest {
    val calls = mutableListOf<String>()
    val client = clientOf(
      models = listOf("empty-1", "empty-2"),
      engine = engineFor(mapOf("empty-1" to emptySse, "empty-2" to emptySse), calls),
    )

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.NIM).toList()

    // Цепочка исчерпана — пользователь должен увидеть ошибку, а не немой экран.
    assertEquals(listOf("empty-1", "empty-2"), calls)
    assertTrue("исчерпанная цепочка обязана дать Error", events.any { it is StreamEvent.Error })
  }
}
