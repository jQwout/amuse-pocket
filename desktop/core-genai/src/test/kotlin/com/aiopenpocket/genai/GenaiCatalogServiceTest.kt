package com.aiopenpocket.genai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
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

  private fun jsonEngine(body: String) = MockEngine {
    respond(
      content = ByteReadChannel(body),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
  }

  private fun cacheFile(): File = File(createTempDirectory("genai-catalog").toFile(), "cache.json")

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
    assertEquals(listOf("vendor/vid-live"), catalog.forModality(GenerationModality.VIDEO).map { it.id })
    assertEquals(listOf("vendor/tts-live"), catalog.forModality(GenerationModality.AUDIO).map { it.id })
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
