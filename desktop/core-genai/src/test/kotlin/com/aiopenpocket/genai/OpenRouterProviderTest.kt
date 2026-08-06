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
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
      {"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}],
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
        attachments = listOf(
          InputAttachment("https://example.com/last.png", AttachmentRole.LAST_FRAME),
        ),
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
  fun `every attachment travels as its own input reference`() = runTest {
    // Тело ответа — от РУЧКИ КАРТИНОК. С прежним чат-ответом тест проходил вхолостую: он смотрит
    // только на запрос, а ответ, который не разбирается, этого не показывал.
    val engine = jsonEngine("""{"data":[{"b64_json":"aGVsbG8="}]}""")

    provider(engine).generate(
      GenerationRequest(
        modality = GenerationModality.IMAGE,
        prompt = "коллаж",
        model = "vendor/img",
        attachments = listOf(
          InputAttachment("https://example.com/bg.jpg", AttachmentRole.BACKGROUND),
          InputAttachment("https://example.com/hero.png", AttachmentRole.CHARACTER),
        ),
      ),
    ).toList()

    val sent = sentBody(engine)
    // Именно в input_references, а не просто «где-то в теле»: без этой проверки удаление поля из
    // запроса осталось бы незамеченным — имена файлов есть и в тексте промпта.
    assertTrue(sent.contains("input_references"), sent)
    val references = sent.substringAfter("input_references")
    assertTrue(references.contains("bg.jpg"), sent)
    assertTrue(references.contains("hero.png"), sent)
  }

  @Test
  fun `roles and tags of an image reach the model as words in the prompt`() = runTest {
    // Поля для РОЛИ картинки нет ни у чата, ни у /api/v1/images: сами картинки едут настоящим
    // input_references, а чем каждая является — сказать нечем. Роль остаётся подсказкой в тексте,
    // и если она туда не попадёт, «Фон» в интерфейсе окажется контролом без последствий.
    val engine = jsonEngine("""{"data":[{"b64_json":"aGVsbG8="}]}""")

    provider(engine).generate(
      GenerationRequest(
        modality = GenerationModality.IMAGE,
        prompt = "ночная трасса",
        model = "vendor/img",
        attachments = listOf(
          InputAttachment("https://example.com/trassa_fog_11.jpg", AttachmentRole.BACKGROUND, listOf("ночь", "туман")),
        ),
      ),
    ).toList()

    val sent = sentBody(engine)
    assertTrue(sent.contains("фон: trassa_fog_11.jpg"), sent)
    assertTrue(sent.contains("ночь, туман"), sent)
  }

  @Test
  fun `for video the roles split into frame_images and input_references`() = runTest {
    // Здесь роли настоящие: кадры и стилевые референсы — разные поля схемы, и складывать их
    // в одну кучу значит просить у провайдера не то, что показано в интерфейсе.
    val engine = jsonEngine(
      """{"id":"v1","status":"completed","unsigned_urls":["https://openrouter.ai/v/1.mp4"]}""",
    )

    provider(engine).generate(
      GenerationRequest(
        modality = GenerationModality.VIDEO,
        prompt = "оживи",
        model = "vendor/veo",
        attachments = listOf(
          InputAttachment("https://example.com/first.png", AttachmentRole.FIRST_FRAME),
          InputAttachment("https://example.com/style.png", AttachmentRole.STYLE),
        ),
      ),
    ).toList()

    val sent = sentBody(engine)
    assertTrue(sent.contains("\"frame_type\":\"first_frame\""), sent)
    assertTrue(sent.contains("\"input_references\""), sent)
    assertTrue(sent.contains("style.png"), sent)
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
    // Ручка картинок отвечает 200 с пустым data — это молчание модели, а не результат.
    val engine = jsonEngine("""{"created":1,"data":[]}""")
    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "запрещённое", "black-forest-labs/flux.2-pro"),
    ).toList()

    val failed = events.filterIsInstance<GenerationProgress.Failed>().single()
    assertEquals("no_image", failed.code)
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
    val engine = jsonEngine("""{"data":[{"b64_json":"aGVsbG8="}]}""")
    provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "black-forest-labs/flux.2-pro"),
    ).toList()

    val request = engine.requestHistory.single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("Bearer sk-test", request.headers["Authorization"])
    assertEquals("AI Open Pocket Desktop", request.headers["X-Title"])
    // Своя ручка, а не chat/completions: только у неё есть поля разрешения, формата и соотношения.
    assertTrue(request.url.toString().endsWith("/images"), request.url.toString())
  }

  @Test
  fun `a plain image url keeps its own type instead of becoming png`() = runTest {
    // Часть провайдеров отдаёт ссылку вместо байтов — разбирать надо оба варианта.
    val engine = jsonEngine("""{"data":[{"url":"https://cdn.example/pic.jpg?v=2"}]}""")
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

  @Test
  fun `polling never sends the key to a host that is not OpenRouter`() = runTest {
    // polling_url приходит из ответа провайдера, ЛОЖИТСЯ В jobs.json и переигрывается при каждом
    // запуске. Один битый ответ или одна правка файла руками — и ключ уходил бы на чужой хост
    // снова и снова. Отказываемся молча наружу, но громко в статусе задания.
    var requests = 0
    val engine = MockEngine {
      requests++
      respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val provider = OpenRouterProvider(engine = engine, apiKey = { "sk-or-secret" })

    val events = provider.resume("https://evil.example/steal").toList()

    assertEquals(0, requests, "на чужой хост не должно уйти ни одного запроса")
    val failed = events.filterIsInstance<GenerationProgress.Failed>().single()
    assertEquals("bad_polling_host", failed.code)
    provider.close()
  }

  @Test
  fun `userinfo trick does not make a foreign host look like ours`() {
    // Наивная проверка «содержит openrouter.ai» тут ошибается: адрес ведёт на evil.example.
    assertFalse(isOpenRouterHost("https://openrouter.ai@evil.example/x"))
    assertFalse(isOpenRouterHost("https://openrouter.ai.evil.example/x"))
    assertFalse(isOpenRouterHost("не адрес вовсе"))
    assertFalse(isOpenRouterHost(""))

    assertTrue(isOpenRouterHost("https://openrouter.ai/api/v1/videos/abc"))
    assertTrue(isOpenRouterHost("https://cdn.openrouter.ai/file.mp4"))
    assertTrue(isOpenRouterHost("https://OPENROUTER.AI/x"))
  }

  @Test
  fun `images go to the dedicated endpoint with the declared parameters`() = runTest {
    // Через chat/completions разрешение и формат положить некуда — у чата таких полей нет.
    // Ради них (и ради сорока моделей из /api/v1/images/models) генерация и переехала на свою ручку.
    var path: String? = null
    var sent: String? = null
    val encoded = java.util.Base64.getEncoder().encodeToString("кадр".toByteArray())
    val engine = MockEngine { request ->
      path = request.url.encodedPath
      sent = (request.body as TextContent).text
      respond(
        """{"data":[{"b64_json":"$encoded","media_type":"image/webp"}],"usage":{"cost":0.075}}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val provider = OpenRouterProvider(engine = engine, apiKey = { "sk-or-test" })

    val events = provider.generate(
      GenerationRequest(
        modality = GenerationModality.IMAGE,
        prompt = "кот",
        model = "qwen/qwen-image-3-pro",
        resolution = "2K",
        aspectRatio = "16:9",
        outputFormat = "webp",
      ),
    ).toList()

    assertEquals("/api/v1/images", path)
    assertTrue("\"resolution\":\"2K\"" in sent!!, sent!!)
    assertTrue("\"aspect_ratio\":\"16:9\"" in sent!!, sent!!)
    assertTrue("\"output_format\":\"webp\"" in sent!!, sent!!)

    val done = events.filterIsInstance<GenerationProgress.Completed>().single()
    assertEquals(0.075, done.result.costUsd)
    val bytes = done.result.outputs.single() as GenerationOutput.Bytes
    assertEquals("кадр", String(bytes.data))
    assertEquals("image/webp", bytes.mimeType)
    provider.close()
  }

  @Test
  fun `parameters the user did not choose are not sent at all`() = runTest {
    // «Не выбрано» и «модель не заявляла» дают одно и то же: поля в запросе нет, провайдер берёт
    // своё умолчание. Слать туда выдуманное значение — получать отказ за деньги.
    var sent: String? = null
    val engine = MockEngine { request ->
      sent = (request.body as TextContent).text
      respond(
        """{"data":[{"b64_json":"eA=="}]}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    val provider = OpenRouterProvider(engine = engine, apiKey = { "sk-or-test" })

    provider.generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "vendor/model"),
    ).toList()

    assertFalse("resolution" in sent!!, sent!!)
    assertFalse("aspect_ratio" in sent!!, sent!!)
    assertFalse("output_format" in sent!!, sent!!)
    assertFalse("input_references" in sent!!, sent!!)
    provider.close()
  }

  @Test
  fun `fractional token counts do not throw away a paid image`() = runTest {
    // Живой ответ 2026-08-05: "total_tokens":13680.64. С Int разбор падал целиком — вместе с уже
    // оплаченной картинкой и её ценой в том же теле. Деньги списаны, результат выброшен.
    val engine = jsonEngine(
      """
      {"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}],
       "usage":{"prompt_tokens":5488.64,"total_tokens":13680.64,"cost":0.075,"is_byok":false}}
      """.trimIndent(),
    )

    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "vendor/img"),
    ).toList()

    val done = events.filterIsInstance<GenerationProgress.Completed>().single()
    assertEquals(0.075, done.result.costUsd)
    assertEquals("hello", String((done.result.outputs.single() as GenerationOutput.Bytes).data))
  }

  @Test
  fun `a schema surprise does not cost a paid image`() = runTest {
    // Тот самый случай, только злее: пусть в ответе будет поле, которого схема не знает ВООБЩЕ и
    // тип которого её ломает. Картинка и цена в теле есть — значит и результат обязан быть.
    // За тело уже заплачено; терять его из-за поля, которое мы не читаем, нельзя.
    val engine = jsonEngine(
      """
      {"created":1,"data":[{"b64_json":"aGVsbG8=","media_type":"image/png"}],
       "usage":{"total_tokens":13680.64,"cost":0.075},
       "provider_meta":{"latency":[1,2,{"nested":"что угодно"}]},"stats":нечисло}
      """.trimIndent(),
    )

    val events = provider(engine).generate(
      GenerationRequest(GenerationModality.IMAGE, "кот", "vendor/img"),
    ).toList()

    val done = events.filterIsInstance<GenerationProgress.Completed>().single()
    assertEquals("hello", String((done.result.outputs.single() as GenerationOutput.Bytes).data))
    assertEquals(0.075, done.result.costUsd, "цену тоже вытаскиваем: она уже списана")
  }

  @Test
  fun `salvage takes the image url when there are no bytes`() {
    val outputs = salvageImages("""{"data":[{"url":"https://cdn.example/pic.jpg"}],"stats":сломано}""")

    val remote = outputs.single() as GenerationOutput.RemoteUrl
    assertEquals("https://cdn.example/pic.jpg", remote.url)
  }

  @Test
  fun `salvage finds nothing in a body without images`() {
    // Пусто должно означать пусто: выдумывать результат там, где его нет, хуже, чем отказ.
    assertTrue(salvageImages("""{"error":{"message":"нет провайдера"}}""").isEmpty())
    assertNull(salvageCost("""{"data":[]}"""))
  }
}
