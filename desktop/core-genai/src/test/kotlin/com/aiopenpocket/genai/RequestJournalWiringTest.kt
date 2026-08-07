package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Журнал проверяется НА ПРОВАЙДЕРЕ, а не только на чистых функциях: чистка тел бесполезна, если
 * записать забыли или записали мимо неё. Здесь же ловится и вторая ошибка, которую легко сделать, —
 * прочитать тело ответа дважды: журналу и разбору достаётся один и тот же текст, и если чтение
 * разъедется, картинка до пользователя не доедет.
 */
class RequestJournalWiringTest {

  private fun okEngine(body: String) = MockEngine { _ ->
    respond(
      content = ByteReadChannel(body),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
  }

  private val imageAnswer = """
    {"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}],"usage":{"cost":0.0042}}
  """.trimIndent()

  private fun request(prompt: String = "мокрый асфальт") = GenerationRequest(
    modality = GenerationModality.IMAGE,
    prompt = prompt,
    model = "black-forest-labs/flux.2-pro",
  )

  @Test
  fun `a generation lands in the journal with the prompt and the cost`() = runTest {
    val journal = InMemoryRequestJournal().apply { enabled = true }
    val provider = OpenRouterProvider(
      engine = okEngine(imageAnswer),
      apiKey = { "sk-or-v1-secret" },
      journal = journal,
    )

    val progress = provider.generate(request()).toList()

    val entry = journal.entries.value.single()
    assertEquals("POST", entry.method)
    assertEquals(200, entry.status)
    assertEquals(GenerationModality.IMAGE, entry.modality)
    assertEquals("black-forest-labs/flux.2-pro", entry.model)
    assertTrue(entry.requestBody.contains("мокрый асфальт"), "промпт не виден: ${entry.requestBody}")
    assertEquals(0.0042, entry.costUsd)
    assertFalse(entry.failed)
    // И картинка при этом дошла: журнал не должен съедать тело ответа.
    assertTrue(progress.last() is GenerationProgress.Completed, "результат потерялся: ${progress.last()}")
  }

  @Test
  fun `the key never reaches the journal even when the provider echoes it back`() = runTest {
    // Провайдеры любят возвращать эхо запроса в тексте ошибки — вместе с заголовком.
    val journal = InMemoryRequestJournal().apply { enabled = true }
    val echo = """{"error":{"message":"bad auth: Bearer sk-or-v1-secret"}}"""
    val provider = OpenRouterProvider(
      engine = MockEngine { _ ->
        respond(
          content = ByteReadChannel(echo),
          status = HttpStatusCode.Unauthorized,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      },
      apiKey = { "sk-or-v1-secret" },
      journal = journal,
    )

    provider.generate(request()).toList()

    val entry = journal.entries.value.single()
    assertFalse(entry.responseBody.contains("sk-or-v1-secret"), "ключ утёк: ${entry.responseBody}")
    assertTrue(entry.responseBody.contains("Bearer ***"))
    assertEquals(401, entry.status)
    assertTrue(entry.failed)
  }

  @Test
  fun `a failed request is journaled too, with the provider answer`() = runTest {
    val journal = InMemoryRequestJournal().apply { enabled = true }
    val provider = OpenRouterProvider(
      engine = MockEngine { _ -> respondError(HttpStatusCode.PaymentRequired) },
      apiKey = { "sk-test" },
      journal = journal,
    )

    val progress = provider.generate(request()).toList()

    // Отказ — самое интересное в журнале: ради него его и открывают.
    assertEquals(402, journal.entries.value.single().status)
    assertTrue(progress.last() is GenerationProgress.Failed)
  }

  @Test
  fun `with the journal off the provider works exactly the same`() = runTest {
    val provider = OpenRouterProvider(engine = okEngine(imageAnswer), apiKey = { "sk-test" })

    val progress = provider.generate(request()).toList()

    assertTrue(progress.last() is GenerationProgress.Completed)
  }

  @Test
  fun `an attached image is folded down before it is stored`() = runTest {
    val journal = InMemoryRequestJournal().apply { enabled = true }
    val provider = OpenRouterProvider(
      engine = okEngine(imageAnswer),
      apiKey = { "sk-test" },
      journal = journal,
    )
    // НАСТОЯЩИЙ файл на диске, а не публичный URL: ссылка проходит сквозь resolveInputImage без
    // изменений, и тест на ней проходил бы вхолостую — сворачивать было бы нечего.
    val png = File(createTempDirectory("journal-ref").toFile(), "ref.png")
    png.writeBytes(ByteArray(30 * 1024) { (it % 251).toByte() })
    val encodedLength = imageFileToDataUrl(png).length
    assertTrue(encodedLength > 40_000, "тестовая картинка вышла слишком мелкой: $encodedLength")

    provider.generate(request().copy(inputImage = png.absolutePath)).toList()

    val body = journal.entries.value.single().requestBody
    assertTrue(body.contains("base64, 30 КБ свёрнуто"), "картинка не свернулась: ${body.take(200)}")
    assertTrue(body.length < 1_000, "тело осталось тяжёлым: ${body.length} против $encodedLength")
    assertTrue(body.contains("мокрый асфальт"), "вместе с картинкой свернулся и промпт")
  }
}
