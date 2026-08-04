package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Три протокола OpenRouter под одним интерфейсом проверяются без сети: движок Ktor подменён
 * MockEngine'ом — тот же приём, что в тестах OpenAiCompatClient у :app.
 */
class OpenRouterProviderTest {

  private fun jsonEngine(vararg bodies: String) = MockEngine { _ ->
    val index = (callCount++).coerceAtMost(bodies.lastIndex)
    respond(
      content = ByteReadChannel(bodies[index]),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
  }

  private var callCount = 0

  private fun provider(engine: MockEngine, key: String = "sk-test") =
    OpenRouterProvider(engine = engine, apiKey = { key }, pollIntervalMs = 0, maxPollAttempts = 5)

  @Test
  fun `image arrives as a data URL and becomes bytes`() = runTest {
    val engine = jsonEngine(
      """
      {"model":"black-forest-labs/flux.2-pro",
       "choices":[{"message":{"role":"assistant","content":"готово",
         "images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8="}}]}}],
       "usage":{"cost":0.0042}}
      """.trimIndent(),
    )
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот в скафандре", "black-forest-labs/flux.2-pro"),
    ).toList()

    val completed = events.filterIsInstance<GenerationProgress.Completed>().single()
    val output = completed.result.outputs.single() as GenerationOutput.Bytes
    assertEquals("hello", String(output.data))
    assertEquals("image/png", output.mimeType)
    assertEquals(0.0042, completed.result.costUsd)
  }

  private fun sentBody(engine: MockEngine): String =
    (engine.requestHistory.first().body as TextContent).text

  @Test
  fun `video request carries duration, resolution and aspect ratio`() = runTest {
    val engine = jsonEngine(
      """{"id":"v1","status":"completed","unsigned_urls":["https://openrouter.ai/v/1.mp4"]}""",
    )

    provider(engine).generate(
      GenerationRequest(
        modality = GenerationModality.VIDEO,
        prompt = "кот в скафандре",
        model = "vendor/veo",
        durationSeconds = 8,
        resolution = "1080p",
        aspectRatio = "21:9",
      ),
    ).toList()

    val sent = sentBody(engine)
    assertTrue(sent.contains("\"duration\":8"), sent)
    assertTrue(sent.contains("\"resolution\":\"1080p\""), sent)
    assertTrue(sent.contains("\"aspect_ratio\":\"21:9\""), sent)
  }

  @Test
  fun `frame images travel as frame_images with their frame_type`() = runTest {
    // Раньше картинка уезжала верхним полем image_url — этого поля у /videos нет вовсе, схема
    // требует массив frame_images с frame_type. Догадка стояла в README под сомнением; сверено
    // с документацией 2026-08-04.
    val engine = jsonEngine(
      """{"id":"v1","status":"completed","unsigned_urls":["https://openrouter.ai/v/1.mp4"]}""",
    )

    provider(engine).generate(
      GenerationRequest(
        modality = GenerationModality.VIDEO,
        prompt = "оживи кадр",
        model = "vendor/veo",
        inputImage = "https://example.com/first.png",
        lastFrameImage = "https://example.com/last.png",
      ),
    ).toList()

    val sent = sentBody(engine)
    assertTrue(sent.contains("\"frame_type\":\"first_frame\""), sent)
    assertTrue(sent.contains("\"frame_type\":\"last_frame\""), sent)
    assertTrue(sent.contains("\"frame_images\""), sent)
  }

  @Test
  fun `a video without frames sends no frame_images at all`() = runTest {
    // Пустой массив — не то же самое, что его отсутствие: модель без исходных кадров не должна
    // получать признак image-to-video.
    val engine = jsonEngine(
      """{"id":"v1","status":"completed","unsigned_urls":["https://openrouter.ai/v/1.mp4"]}""",
    )

    provider(engine).generate(
      GenerationRequest(GenerationModality.VIDEO, "по промпту", "vendor/veo"),
    ).toList()

    assertTrue(!sentBody(engine).contains("frame_images"), sentBody(engine))
  }

  @Test
  fun `an exhausted poll leaves the video recoverable, not buried in a failure`() = runTest {
    // Видео уже оплачено, polling_url сохранён. Терминальный отказ здесь означает, что
    // resumeUnfinished() задание больше не подхватит (он пропускает терминальные), а единственная
    // кнопка у пользователя — «Повторить», то есть заплатить второй раз за то же самое.
    val engine = jsonEngine(
      """{"id":"vid-1","polling_url":"https://openrouter.ai/api/v1/videos/vid-1","status":"processing"}""",
    )

    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.VIDEO, "кот в скафандре", "google/veo-3.1-fast"),
    ).toList()

    assertTrue(
      events.filterIsInstance<GenerationProgress.Failed>().isEmpty(),
      "исчерпанный опрос похоронил оплаченное видео в терминальном отказе",
    )
    assertTrue(events.last() is GenerationProgress.Status, "последним пришло ${events.last()}")
  }

  @Test
  fun `HTTP 200 without an image counts as a refusal, not a result`() = runTest {
    val engine = jsonEngine(
      """{"choices":[{"message":{"role":"assistant","content":"не могу нарисовать это"}}]}""",
    )
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "запрещённое", "black-forest-labs/flux.2-pro"),
    ).toList()

    val failed = events.filterIsInstance<GenerationProgress.Failed>().single()
    assertEquals("no_image", failed.code)
    // Текст модели попадает в сообщение — именно там объясняются отказы по контент-политике.
    assertTrue(failed.message.contains("не могу нарисовать"), failed.message)
    assertTrue(events.none { it is GenerationProgress.Completed })
  }

  @Test
  fun `audio is read as raw bytes, not JSON`() = runTest {
    val engine = MockEngine { _ ->
      respond(
        content = ByteReadChannel(byteArrayOf(0x49, 0x44, 0x33, 0x04)),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
      )
    }
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.AUDIO, "привет", "openai/gpt-4o-mini-tts", voice = "nova"),
    ).toList()

    val output = events.filterIsInstance<GenerationProgress.Completed>()
      .single().result.outputs.single() as GenerationOutput.Bytes
    assertEquals(4, output.data.size)
    assertEquals(OpenRouterProvider.MIME_MP3, output.mimeType)
  }

  @Test
  fun `video goes accepted then polling then completed`() = runTest {
    val engine = jsonEngine(
      """{"id":"abc123","polling_url":"https://openrouter.ai/api/v1/videos/abc123","status":"pending"}""",
      """{"id":"abc123","status":"in_progress"}""",
      """
      {"id":"abc123","status":"completed",
       "unsigned_urls":["https://openrouter.ai/api/v1/videos/abc123/content?index=0"],
       "usage":{"cost":0.25}}
      """.trimIndent(),
    )
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.VIDEO, "золотистый ретривер на пляже", "google/veo-3.1-fast"),
    ).toList()

    val accepted = events.filterIsInstance<GenerationProgress.Accepted>().single()
    assertEquals("abc123", accepted.providerJobId)
    // pollingUrl обязан дойти до очереди: без него задание нельзя догнать после перезапуска.
    assertEquals("https://openrouter.ai/api/v1/videos/abc123", accepted.pollingUrl)
    assertTrue(events.any { it is GenerationProgress.Status }, "промежуточный опрос не виден в прогрессе")

    val completed = events.filterIsInstance<GenerationProgress.Completed>().single()
    val output = completed.result.outputs.single() as GenerationOutput.RemoteUrl
    assertTrue(output.url.endsWith("content?index=0"))
    assertEquals(0.25, completed.result.costUsd)
  }

  @Test
  fun `failed video status stops the polling loop`() = runTest {
    val engine = jsonEngine(
      """{"id":"x","polling_url":"https://openrouter.ai/api/v1/videos/x","status":"pending"}""",
      """{"id":"x","status":"failed","error":"content policy"}""",
    )
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.VIDEO, "что-то не то", "google/veo-3.1-fast"),
    ).toList()

    val failed = events.filterIsInstance<GenerationProgress.Failed>().single()
    assertEquals("failed", failed.code)
    // POST + один опрос: после терминального статуса запросов больше нет.
    assertEquals(2, engine.requestHistory.size)
  }

  @Test
  fun `insufficient balance is explained in plain words`() = runTest {
    val engine = MockEngine { _ -> respondError(HttpStatusCode.PaymentRequired) }
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "black-forest-labs/flux.2-pro"),
    ).toList()

    val failed = events.filterIsInstance<GenerationProgress.Failed>().single()
    assertEquals("402", failed.code)
    assertTrue(failed.message.contains("недостаточно средств"), failed.message)
  }

  @Test
  fun `blank key spends no request`() = runTest {
    val engine = MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }
    val events = provider(engine, key = "  ").generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "black-forest-labs/flux.2-pro"),
    ).toList()

    assertEquals("blank_key", events.filterIsInstance<GenerationProgress.Failed>().single().code)
    assertEquals(0, engine.requestHistory.size)
  }

  @Test
  fun `key and attribution go out in headers`() = runTest {
    val engine = jsonEngine(
      """{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,aGVsbG8="}}]}}]}""",
    )
    provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "black-forest-labs/flux.2-pro"),
    ).toList()

    val request = engine.requestHistory.single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("Bearer sk-test", request.headers["Authorization"])
    assertEquals("AI Open Pocket Desktop", request.headers["X-Title"])
    // modalities обязателен: без него chat/completions вернёт текст вместо картинки.
    assertTrue(request.url.toString().endsWith("/chat/completions"))
  }

  @Test
  fun `a plain image url keeps its own type instead of becoming png`() = runTest {
    val engine = jsonEngine(
      """{"choices":[{"message":{"images":[{"image_url":{"url":"https://cdn.example/pic.jpg?v=2"}}]}}]}""",
    )
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "black-forest-labs/flux.2-pro"),
    ).toList()

    val output = events.filterIsInstance<GenerationProgress.Completed>()
      .single().result.outputs.single() as GenerationOutput.RemoteUrl
    // Раньше тип был жёстко image/png, и jpeg сохранялся под именем .png.
    assertEquals("image/jpeg", output.mimeType)
  }

  @Test
  fun `access check reads key info out of the data envelope`() = runTest {
    val engine = jsonEngine(
      """
      {"data":{"label":"личный ключ","usage":1.25,"limit":10.0,"is_free_tier":false,
        "rate_limit":{"requests":10,"interval":"10s"}}}
      """.trimIndent(),
    )
    val ok = provider(engine).checkAccess() as AccessCheck.Ok

    assertEquals("личный ключ", ok.label)
    assertEquals(1.25, ok.usageUsd)
    assertEquals(10.0, ok.limitUsd)
    assertEquals(false, ok.freeTier)
    assertTrue(ok.rateLimit!!.contains("10s"), ok.rateLimit!!)
    assertTrue(engine.requestHistory.single().url.toString().endsWith("/key"))
  }

  @Test
  fun `access check also accepts a flat response without the envelope`() = runTest {
    // Форма конверта не должна решать, работает проверка связи или нет.
    val engine = jsonEngine("""{"label":"плоский","usage":0.0}""")
    val ok = provider(engine).checkAccess() as AccessCheck.Ok
    assertEquals("плоский", ok.label)
  }

  @Test
  fun `access check explains a rejected key instead of throwing`() = runTest {
    val engine = MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }
    val failed = provider(engine).checkAccess() as AccessCheck.Failed

    assertEquals("401", failed.code)
    assertTrue(failed.message.contains("не принял ключ"), failed.message)
  }

  @Test
  fun `access check without a key spends no request`() = runTest {
    val engine = MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }
    val failed = provider(engine, key = "").checkAccess() as AccessCheck.Failed

    assertEquals("blank_key", failed.code)
    assertEquals(0, engine.requestHistory.size)
  }

  @Test
  fun `resume catches up a job from the stored url`() = runTest {
    val engine = jsonEngine(
      """{"id":"abc","status":"completed","unsigned_urls":["https://x/y.mp4"]}""",
    )
    val events = provider(engine).resume("https://openrouter.ai/api/v1/videos/abc").toList()

    val output = events.filterIsInstance<GenerationProgress.Completed>()
      .single().result.outputs.single() as GenerationOutput.RemoteUrl
    assertEquals("https://x/y.mp4", output.url)
    assertEquals(HttpMethod.Get, engine.requestHistory.single().method)
  }
}
