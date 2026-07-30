package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Входная картинка ездит по системе ПУТЁМ, а разворачивается в base64 только перед самим запросом.
 * Иначе история на диске распухала бы на мегабайты и переписывалась такой на каждом переходе.
 */
class InputImageTest {

  private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

  // Те же настройки, что у JobStore: проверяем именно то, что уедет в файл истории.
  private val historyJson = Json { encodeDefaults = true }

  private fun tempPng(): java.nio.file.Path {
    val file = createTempDirectory("genai-input").resolve("source.png")
    Files.write(file, pngBytes)
    return file
  }

  @Test
  fun `a job stores the path, not megabytes of base64`() {
    val file = tempPng()
    val job = GenerationJob(
      id = "job1",
      request = GenerationRequest(GenerationModality.IMAGE, "кот", "модель", inputImage = file.toString()),
      createdAtEpochMs = 1L,
    )

    val encoded = historyJson.encodeToString(listOf(job))
    assertTrue(encoded.contains("source.png"), "путь не сохранился")
    assertFalse(encoded.contains("base64"), "в историю утекло содержимое картинки")
  }

  @Test
  fun `a local path becomes a data url only at request time`() = runTest {
    val file = tempPng()
    val engine = MockEngine { _ ->
      respond(
        content = ByteReadChannel(
          """{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,aGVsbG8="}}]}}]}""",
        ),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val provider = OpenRouterProvider(engine = engine, apiKey = { "sk-test" })

    provider.generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "модель", inputImage = file.toString()),
    ).toList()

    val body = (engine.requestHistory.single().body as TextContent).text
    // В запрос ушёл base64, а не путь с машины пользователя.
    assertTrue(body.contains("data:image/png;base64,"), "картинка не развернулась в запрос")
    assertFalse(body.contains("source.png"), "в запрос утёк локальный путь")
  }

  @Test
  fun `a remote link is passed through untouched`() = runTest {
    assertEquals("https://cdn.example/pic.jpg", resolveInputImage("https://cdn.example/pic.jpg"))
    assertEquals("data:image/png;base64,aGk=", resolveInputImage("data:image/png;base64,aGk="))
  }

  @Test
  fun `a missing file fails the job instead of silently generating without it`() = runTest {
    val engine = MockEngine { _ ->
      respond(content = ByteReadChannel("{}"), status = HttpStatusCode.OK)
    }
    val provider = OpenRouterProvider(engine = engine, apiKey = { "sk-test" })

    val events = provider.generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "модель", inputImage = "/нет/такого/файла.png"),
    ).toList()

    assertTrue(events.filterIsInstance<GenerationProgress.Failed>().isNotEmpty())
    // Молча сгенерировать «просто по тексту» нельзя: пользователь просил именно эту основу.
    assertEquals(0, engine.requestHistory.size)
  }
}
