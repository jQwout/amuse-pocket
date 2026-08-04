package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Живой каталог генеративных моделей — без сети, транспорт подменён MockEngine'ом, как и в
 * остальных тестах ядра.
 */
class GenaiCatalogServiceTest {

  /**
   * Эндпоинтов два, и отвечать одним телом на оба нельзя: картиночный ответ разобрался бы и как
   * видео-каталог, а тест бы этого не заметил. По умолчанию видео-каталог пуст — так ведёт себя
   * учётная запись без доступа к видео, и так же выглядел бы отказ этого эндпоинта.
   */
  private fun jsonEngine(body: String, videoModels: String = """{"data":[]}""") =
    routedEngine(models = body, videoModels = videoModels)

  private fun cacheFile(): File = File(createTempDirectory("genai-catalog").toFile(), "cache.json")

  /** Два эндпоинта на одном движке: обычный каталог и отдельный видео-каталог. */
  private fun routedEngine(models: String, videoModels: String) = MockEngine { request ->
    val body = if (request.url.encodedPath.endsWith("/videos/models")) videoModels else models
    respond(
      content = ByteReadChannel(body),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
  }

  private val videoBody = """
    {"data":[
      {"id":"vendor/veo","supported_resolutions":["720p","1080p"],
       "supported_aspect_ratios":["16:9","9:16"],"supported_durations":[5,10],
       "supported_frame_images":["first_frame","last_frame"],"generate_audio":true,"seed":false},
      {"id":"vendor/aleph","supported_resolutions":null,"supported_aspect_ratios":["16:9"],
       "supported_durations":null,"supported_frame_images":null,"generate_audio":false,"seed":true}
    ]}
  """.trimIndent()

  @Test
  fun `video models come from the dedicated endpoint with their capabilities`() = runTest {
    // В обычном /api/v1/models видео нет вовсе — проверено на живом API 2026-08-04. Раньше
    // видео-подсказки держались на сиде, то есть на вручную вписанных слагах.
    val catalog = requireNotNull(
      GenaiCatalogService(
        engine = routedEngine(liveBody, videoBody),
        cacheFile = cacheFile(),
        seed = { GenaiModelCatalog() },
      ).refresh(now = 1_000),
    )

    val ids = catalog.forModality(GenerationModality.VIDEO).map { it.id }
    assertEquals(listOf("vendor/veo", "vendor/aleph"), ids)

    val veo = catalog.forModality(GenerationModality.VIDEO).first().video
    assertEquals(listOf(5, 10), veo?.durations)
    assertEquals(listOf("720p", "1080p"), veo?.resolutions)
    assertEquals(listOf("16:9", "9:16"), veo?.aspectRatios)
    assertEquals(true, veo?.supportsLastFrame)
  }

  @Test
  fun `a video model without declared capabilities offers no fake controls`() = runTest {
    // У части моделей поля приходят null. Показать им «720p / 5 с» значило бы предложить то,
    // чего провайдер не обещал.
    val catalog = requireNotNull(
      GenaiCatalogService(
        engine = routedEngine(liveBody, videoBody),
        cacheFile = cacheFile(),
        seed = { GenaiModelCatalog() },
      ).refresh(now = 1_000),
    )

    val aleph = catalog.forModality(GenerationModality.VIDEO).single { it.id == "vendor/aleph" }.video
    assertEquals(emptyList(), aleph?.durations)
    assertEquals(emptyList(), aleph?.resolutions)
    assertEquals(false, aleph?.supportsLastFrame)
  }

  /** Встроенные подсказки в тестах — маленькие и свои, чтобы тест не ломался от правок models.json. */
  private val seed = GenaiModelCatalog(
    models = listOf(
      GenaiModelEntry("vendor/img-seed", GenerationModality.IMAGE, priority = 1, label = "Картинка из сида"),
      GenaiModelEntry("vendor/vid-seed", GenerationModality.VIDEO, priority = 1, label = "Видео из сида"),
      GenaiModelEntry(
        "vendor/tts-live",
        GenerationModality.AUDIO,
        priority = 1,
        label = "Озвучка из сида",
        voices = listOf("alloy", "nova"),
      ),
    ),
  )

  private val liveBody = """
    {"data":[
      {"id":"vendor/img-live","created":300,"architecture":{"output_modalities":["image"]}},
      {"id":"vendor/vid-live","created":200,"architecture":{"output_modalities":["video"]}},
      {"id":"vendor/tts-live","created":100,"architecture":{"output_modalities":["audio"]}},
      {"id":"vendor/chat","created":50,"architecture":{"output_modalities":["text"]}}
    ]}
  """.trimIndent()

  private fun service(
    engine: MockEngine,
    cache: File = cacheFile(),
    seedCatalog: GenaiModelCatalog = seed,
    pinnedCatalog: GenaiModelCatalog = GenaiModelCatalog(),
  ) = GenaiCatalogService(
    engine = engine,
    cacheFile = cache,
    seed = { seedCatalog },
    pinned = { pinnedCatalog },
  )

  @Test
  fun `routing pseudo-models never reach the catalog`() = runTest {
    // openrouter/auto — маршрутизатор, а не генератор: какая модель и по какой цене отработает,
    // заранее неизвестно. По дате появления он всплывал наверх и становился моделью по умолчанию.
    val body = """
      {"data":[
        {"id":"openrouter/auto","created":900,"architecture":{"output_modalities":["image"]}},
        {"id":"openrouter/auto-beta","created":950,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/img-live","created":300,"architecture":{"output_modalities":["image"]}}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(body)).refresh(now = 1_000))

    assertEquals(listOf("vendor/img-live"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `routers from an older cache never reach the catalog`() = runTest {
    // Кеш переживает обновление приложения: список, записанный до появления фильтра, не должен
    // ещё сутки подсовывать маршрутизатор как модель по умолчанию.
    val cache = cacheFile()
    cache.parentFile.mkdirs()
    cache.writeText(
      """
      {"fetched_at":1000,"catalog":{"models":[
        {"id":"openrouter/auto","modality":"IMAGE","priority":1},
        {"id":"vendor/img-live","modality":"IMAGE","priority":2}
      ]}}
      """.trimIndent(),
    )

    val snapshot = service(MockEngine { error("сеть не нужна: кеш свежий") }, cache)
      .refreshIfStale(now = 2_000)

    assertEquals(
      listOf("vendor/img-live"),
      snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id },
    )
  }

  @Test
  fun `models listed by the user survive a live refresh and come first`() = runTest {
    // Свой genai-models.json — прямое указание пользователя, а не подсказка. Живой список не
    // повод молча его выбросить: OpenRouter не перечисляет в /models всё, что умеет обслужить.
    val mine = GenaiModelCatalog(
      models = listOf(GenaiModelEntry("me/my-image", GenerationModality.IMAGE, priority = 1, label = "Моя")),
    )

    val catalog = requireNotNull(
      service(jsonEngine(liveBody), pinnedCatalog = mine).refresh(now = 1_000),
    )

    val images = catalog.forModality(GenerationModality.IMAGE).map { it.id }
    assertEquals("me/my-image", images.firstOrNull(), "свой список моделей потерялся при обновлении")
    assertTrue(images.contains("vendor/img-live"), "живые модели должны добавиться к своим")
  }

  @Test
  fun `live catalog is split by output modality, chat-only models dropped`() = runTest {
    val catalog = requireNotNull(
      service(jsonEngine(liveBody), seedCatalog = GenaiModelCatalog()).refresh(now = 1_000),
    ) { "живой каталог не разобрался" }

    assertEquals(listOf("vendor/img-live"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
    assertEquals(listOf("vendor/tts-live"), catalog.forModality(GenerationModality.AUDIO).map { it.id })
    // Видео из ОБЩЕГО каталога игнорируется, даже если оно там вдруг появится: длительностей и
    // разрешений в нём нет, и такая запись дала бы пустые контролы и дубль по id.
    assertEquals(emptyList(), catalog.forModality(GenerationModality.VIDEO).map { it.id })
  }

  @Test
  fun `a modality absent from the live answer keeps its built-in suggestions`() = runTest {
    // У OpenRouter видео живёт за отдельным /videos, и попадает ли оно в /models — не проверено.
    // Если не попадает, замена каталога целиком отняла бы у пользователя выбор видео-моделей.
    val imageOnly = """
      {"data":[{"id":"vendor/img-live","created":300,"architecture":{"output_modalities":["image"]}}]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(imageOnly)).refresh(now = 1_000))

    assertEquals(listOf("vendor/img-live"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
    assertEquals(
      listOf("vendor/vid-seed"),
      catalog.forModality(GenerationModality.VIDEO).map { it.id },
      "видео-подсказки пропали вместе с обновлением каталога",
    )
  }

  @Test
  fun `label and voices survive from the built-in list`() = runTest {
    // Живой /models про голоса TTS не рассказывает — без переноса из сида выбор голоса опустел бы.
    val catalog = requireNotNull(service(jsonEngine(liveBody)).refresh(now = 1_000))

    val tts = catalog.forModality(GenerationModality.AUDIO).single { it.id == "vendor/tts-live" }
    assertEquals("Озвучка из сида", tts.displayName)
    assertContentEquals(listOf("alloy", "nova"), tts.voices)
  }

  @Test
  fun `the built-in list is not an authoritative source`() = runTest {
    val snapshot = service(MockEngine { respondError(HttpStatusCode.InternalServerError) })
      .refreshIfStale(now = 1_000)

    assertFalse(snapshot.authoritative, "сид выдан за список провайдера")
    assertEquals(seed.models.map { it.id }, snapshot.catalog.models.map { it.id })
  }

  @Test
  fun `a fresh cache is served without going to the network`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody), cache).refresh(now = 1_000)

    val engine = MockEngine { error("сеть дёрнули при свежем кеше") }
    val snapshot = service(engine, cache).refreshIfStale(now = 1_000 + 60_000)

    assertTrue(snapshot.authoritative)
    assertEquals(0, engine.requestHistory.size, "запрос ушёл, хотя кеш ещё свежий")
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `a stale cache is refreshed from the network`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody), cache).refresh(now = 0)

    val newer = """
      {"data":[{"id":"vendor/img-newer","created":900,"architecture":{"output_modalities":["image"]}}]}
    """.trimIndent()
    val snapshot = service(jsonEngine(newer), cache).refreshIfStale(now = 25L * 60 * 60 * 1000)

    assertTrue(snapshot.authoritative)
    assertEquals(listOf("vendor/img-newer"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  /**
   * Единственный тест здесь, который гоняет сервис на настоящих потоках. Остальные однопоточные и
   * гонку записи увидеть не могут в принципе.
   *
   * Гонка достижима не в теории: обновление каталога стартует само при запуске окна, а кнопка
   * «Обновить каталог» доступна пользователю в тот же момент — её флаг защищает только от повторного
   * нажатия, но не от фонового обновления. Два писателя через один `.tmp` — ровно та поломка, на
   * которой уже обожглись в JobStore: второй не находит своего временного файла, уходит в ветку
   * «удалить и переименовать» и сносит уже сохранённый каталог.
   */
  @Test
  fun `concurrent refreshes leave the cache readable and no temp file behind`() {
    val cache = cacheFile()
    cache.parentFile.mkdirs()
    val service = service(jsonEngine(liveBody), cache)

    runBlocking(Dispatchers.Default) {
      List(32) { index -> launch { service.refresh(now = index.toLong()) } }.joinAll()
    }

    assertTrue(cache.isFile, "каталог исчез: писатели снесли файл друг у друга")
    val decoded = Json {
      ignoreUnknownKeys = true
      namingStrategy = JsonNamingStrategy.SnakeCase
    }.decodeFromString<CachedGenaiCatalog>(cache.readText())
    assertTrue(decoded.catalog.models.isNotEmpty(), "кеш разобрался, но пуст")

    val leftovers = cache.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
    assertTrue(leftovers.isEmpty(), "остался временный файл: $leftovers")
  }

  @Test
  fun `a broken answer does not clobber a working cache`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody), cache).refresh(now = 0)

    val broken = service(jsonEngine("{это не json"), cache)
    assertEquals(null, broken.refresh(now = 25L * 60 * 60 * 1000), "мусор в ответе принят за каталог")

    // Кеш пережил неудачное обновление и всё ещё считается списком провайдера.
    val snapshot = broken.refreshIfStale(now = 25L * 60 * 60 * 1000)
    assertTrue(snapshot.authoritative)
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `an answer without usable models does not clobber a working cache`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody), cache).refresh(now = 0)

    // Разобралось, но генеративного нет вовсе — одни чат-модели.
    val chatOnly = """
      {"data":[{"id":"vendor/chat","created":50,"architecture":{"output_modalities":["text"]}}]}
    """.trimIndent()
    assertEquals(null, service(jsonEngine(chatOnly), cache).refresh(now = 25L * 60 * 60 * 1000))

    val snapshot = service(jsonEngine(chatOnly), cache).refreshIfStale(now = 25L * 60 * 60 * 1000)
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `models curated in the built-in list come first, the rest newest-first`() = runTest {
    val body = """
      {"data":[
        {"id":"vendor/img-new","created":900,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/img-old","created":100,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/img-seed","created":1,"architecture":{"output_modalities":["image"]}}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(body)).refresh(now = 1_000))

    assertEquals(
      listOf("vendor/img-seed", "vendor/img-new", "vendor/img-old"),
      catalog.forModality(GenerationModality.IMAGE).map { it.id },
    )
  }

  @Test
  fun `blank ids and duplicates never reach the catalog`() = runTest {
    val body = """
      {"data":[
        {"id":"","created":900,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/img-live","created":300,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/img-live","created":200,"architecture":{"output_modalities":["image"]}}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(body)).refresh(now = 1_000))

    assertEquals(listOf("vendor/img-live"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }
}
