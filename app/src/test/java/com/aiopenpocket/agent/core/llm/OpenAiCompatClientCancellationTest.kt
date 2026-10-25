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
 * Отмена хода — не «провайдер упал». В Kotlin `CancellationException` наследует `Exception`, поэтому
 * общий `catch (e: Exception)` вокруг попытки провайдера ловит и её тоже: нажатие Stop (или новый ход,
 * гасящий предыдущий через `TurnManager.startTurn`) превращалось в «эта модель не ответила, берём
 * следующую» и прогоняло ОСТАВШУЮСЯ ЦЕПОЧКУ, заканчивая всё `StreamEvent.Error("All providers failed")`
 * — из уже отменённого flow.
 *
 * Эталон обработки — рядом, в [com.aiopenpocket.agent.core.mcp.ToolExecutor]: там `CancellationException`
 * перехватывается ПЕРВЫМ и пробрасывается. Здесь фиксируем тот же контракт для транспорта.
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
   * Счётчик обращений к ключу = счётчик ПОПЫТОК провайдеров: `apiKeyResolver()` — первое, что делает
   * итерация цикла, и единственная её часть, которая не приостанавливается (а значит выполняется даже
   * в уже отменённой корутине, где следующий за ней suspend-вызов сразу бросит). Именно поэтому он и
   * показывает, дошёл ли цикл до следующих моделей после отмены.
   */
  private fun config(model: String, attempts: AtomicInteger) = ProviderConfig(
    name = LlmProvider.OPENROUTER,
    baseUrl = "https://nim.test/chat/completions",
    model = model,
    apiKeyResolver = {
      attempts.incrementAndGet()
      "test-key"
    },
  )

  private fun clientOf(models: List<String>, attempts: AtomicInteger, engine: MockEngine) =
    OpenAiCompatClient(
      fallbackChain = ProviderFallbackChain(mapOf(LlmProvider.OPENROUTER to models.map { config(it, attempts) })),
      engine = engine,
      usageRecorder = NoOpLlmUsageRecorder,
    )

  @Test
  fun `cancelling the collector aborts the run instead of walking the rest of the chain`() = runTest {
    val attempts = AtomicInteger(0)
    val firstCallEntered = CompletableDeferred<Unit>()
    // Первая модель «висит» на живом соединении, пока её не отменят, — состояние, в котором юзер и
    // жмёт Stop. Ждём НЕзавершаемый Deferred, а не delay(): виртуальное время runTest промотало бы delay.
    val neverCompletes = CompletableDeferred<Unit>()
    val engine = MockEngine {
      firstCallEntered.complete(Unit)
      neverCompletes.await()
      respond(content = textSse, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    val client = clientOf(listOf("hanging-model", "second-model", "third-model"), attempts, engine)

    val job = launch {
      client.streamChat(emptyList(), emptyList(), LlmProvider.OPENROUTER).toList()
    }
    firstCallEntered.await()
    job.cancelAndJoin()

    // Ровно одна попытка: отмена обязана вынести цикл наружу, а не быть засчитанной как отказ модели.
    assertEquals(1, attempts.get())
  }

  @Test
  fun `cancellation propagates to the caller instead of becoming a StreamEvent Error`() = runTest {
    val attempts = AtomicInteger(0)
    val engine = MockEngine { throw CancellationException("stream cancelled") }
    val client = clientOf(listOf("cancelled-model", "second-model"), attempts, engine)

    // Ловим руками, а не assertThrows: отмена прилетает из движка, сам тестовый job при этом не
    // отменяется, поэтому перехват здесь безопасен и не ломает scope runTest.
    val thrown = try {
      client.streamChat(emptyList(), emptyList(), LlmProvider.OPENROUTER).toList()
      null
    } catch (e: Throwable) {
      e
    }

    assertTrue(
      "отмена обязана дойти до вызывающего, а не превратиться в событие: $thrown",
      thrown is CancellationException,
    )
    // И, разумеется, не должна быть засчитана как «модель не ответила».
    assertEquals(1, attempts.get())
  }

  @Test
  fun `an ordinary provider failure still falls back - cancellation handling must not break that`() = runTest {
    val attempts = AtomicInteger(0)
    val engine = MockEngine { request ->
      val body = request.body.toString()
      if (body.contains("\"model\":\"broken-model\"")) {
        respond(content = "boom", status = HttpStatusCode.InternalServerError)
      } else {
        respond(content = textSse, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
      }
    }
    val client = clientOf(listOf("broken-model", "good-model"), attempts, engine)

    val events = client.streamChat(emptyList(), emptyList(), LlmProvider.OPENROUTER).toList()

    assertEquals(2, attempts.get())
    assertEquals("Привет", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content })
  }
}
