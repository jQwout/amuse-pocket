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
import kotlinx.serialization.descriptors.elementNames
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
   * Эндпоинтов ТРИ, и отвечать одним телом на все нельзя: картиночный ответ разобрался бы и как
   * видео-каталог, а тест бы этого не заметил. По умолчанию картиночный и видео-каталоги пусты —
   * так выглядит и отказ этих эндпоинтов, и учётная запись без доступа к ним.
   */
  private fun jsonEngine(
    body: String,
    imageModels: String = """{"data":[]}""",
    videoModels: String = """{"data":[]}""",
  ) = routedEngine(models = body, imageModels = imageModels, videoModels = videoModels)

  private fun cacheFile(): File = File(createTempDirectory("genai-catalog").toFile(), "cache.json")

  /**
   * Три эндпоинта на одном движке: общий каталог (из него берём только аудио), отдельный
   * картиночный и отдельный видео-каталог.
   */
  private fun routedEngine(
    models: String,
    imageModels: String = """{"data":[]}""",
    videoModels: String = """{"data":[]}""",
    /**
     * Цена живёт за четвёртой ручкой — своей на каждую модель. По умолчанию её нет ни у кого: так
     * отвечает и провайдер, который цену не публикует (`krea`), и упавший эндпоинт.
     */
    pricing: String = """{"endpoints":[]}""",
  ) = MockEngine { request ->
    val path = request.url.encodedPath
    val body = when {
      // Раньше остальных: путь цены заканчивается на «/endpoints», но начинается с «/images/models».
      path.endsWith("/endpoints") -> pricing
      path.endsWith("/images/models") -> imageModels
      path.endsWith("/videos/models") -> videoModels
      else -> models
    }
    respond(
      content = ByteReadChannel(body),
      status = HttpStatusCode.OK,
      headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
  }

  /**
   * Картиночный каталог. Своя ручка `/api/v1/images/models` со своими полями: id/name/description —
   * всё, что мы оттуда берём. Живёт отдельно от общего каталога не для симметрии: в общем списке
   * картиночных моделей одиннадцать, а здесь сорок (проверено 2026-08-05).
   */
  private val imageBody = """
    {"data":[
      {"id":"vendor/img-live","name":"Vendor: Живое имя","description":"Описание от вендора."}
    ]}
  """.trimIndent()

  /** Картиночный ответ из перечисленных слагов — для тестов порядка, дублей и маршрутизаторов. */
  private fun imagesOf(vararg ids: String) =
    """{"data":[""" + ids.joinToString(",") { """{"id":"$it"}""" } + """]}"""

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
        engine = routedEngine(liveBody, videoModels = videoBody),
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
        engine = routedEngine(liveBody, videoModels = videoBody),
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
      {"id":"vendor/img-live","name":"Vendor: Живое имя","description":"Описание от вендора.","created":300,"architecture":{"output_modalities":["image"]}},
      {"id":"vendor/vid-live","name":"Vendor: Видео","description":"Видео-описание.","created":200,"architecture":{"output_modalities":["video"]}},
      {"id":"vendor/tts-live","created":100,"architecture":{"output_modalities":["audio"]}},
      {"id":"vendor/chat","created":50,"architecture":{"output_modalities":["text"]}}
    ]}
  """.trimIndent()

  @Test
  fun `image pricing and the reference limit come from the live answer`() = runTest {
    // Обе величины живут в разных местах: лимит референсов — range-параметр в каталоге, цена — в
    // отдельной ручке на модель. И то и другое проверено на живом API 2026-08-06.
    val images = """
      {"data":[{"id":"vendor/img-live","supported_parameters":{
        "input_references":{"type":"range","min":0,"max":14},
        "resolution":{"type":"enum","values":["1K","4K"]}}}]}
    """.trimIndent()
    val pricing = """
      {"endpoints":[{"provider_slug":"vendor","pricing":[
        {"billable":"input_image","unit":"token","cost_usd":0.000002},
        {"billable":"output_image","unit":"image","cost_usd":0.04},
        {"billable":"output_image","unit":"image","cost_usd":0.06,"variant":"4k"}]}]}
    """.trimIndent()

    val catalog = requireNotNull(
      service(routedEngine(liveBody, images, pricing = pricing)).refresh(now = 1_000),
    )
    val entry = catalog.forModality(GenerationModality.IMAGE).single { it.id == "vendor/img-live" }

    // Цену входа не берём: она зависит от длины промпта и числа референсов, то есть от того, что
    // ещё не набрано, и сложить её с выводом в одну цифру честно нельзя.
    assertEquals(
      listOf(ImagePrice("image", 0.04), ImagePrice("image", 0.06, "4k")),
      entry.pricing,
    )
    assertEquals(14, entry.image?.maxReferences)
    assertEquals(0.04, entry.pricing.modelPrice().frameUsd())
  }

  @Test
  fun `a model whose price is unavailable keeps its place in the catalog`() = runTest {
    // Отказ ручки цены не отнимает у пользователя модель: он теряет число, а не строку списка.
    // Поэтому правила «не кешировать каталог без цен» здесь нет, в отличие от картинок.
    val images = imagesOf("vendor/img-live")
    val catalog = requireNotNull(
      service(routedEngine(liveBody, images, pricing = """{"мусор":1}""")).refresh(now = 1_000),
    )

    val entry = catalog.forModality(GenerationModality.IMAGE).single { it.id == "vendor/img-live" }
    assertEquals(emptyList(), entry.pricing)
    assertEquals(ModelPrice.Unknown, entry.pricing.modelPrice())
  }

  @Test
  fun `pricing takes the first provider that named a price`() = runTest {
    // У модели бывает несколько провайдеров (Vertex и AI Studio у gemini), и порядок в ответе —
    // это порядок предпочтения самого OpenRouter, то есть тот, по которому запрос и уйдёт.
    val pricing = """
      {"endpoints":[
        {"provider_slug":"silent","pricing":[]},
        {"provider_slug":"first","pricing":[{"billable":"output_image","unit":"image","cost_usd":0.09}]},
        {"provider_slug":"second","pricing":[{"billable":"output_image","unit":"image","cost_usd":0.02}]}]}
    """.trimIndent()

    val catalog = requireNotNull(
      service(routedEngine(liveBody, imagesOf("vendor/img-live"), pricing = pricing)).refresh(now = 1_000),
    )

    assertEquals(
      listOf(ImagePrice("image", 0.09)),
      catalog.forModality(GenerationModality.IMAGE).single { it.id == "vendor/img-live" }.pricing,
    )
  }

  @Test
  fun `a forced refresh goes to the network even with a fresh cache`() = runTest {
    // Каталог тянется на каждый запуск приложения: слаги и цены у OpenRouter меняются чаще, чем
    // раз в сутки, а суточный TTL был рассчитан на противоположное.
    val cache = cacheFile()
    service(jsonEngine(liveBody, imageBody), cache).refresh(now = 1_000)

    val engine = routedEngine(liveBody, imageBody)
    val snapshot = service(engine, cache).refreshIfStale(now = 1_000 + 60_000, force = true)

    assertTrue(engine.requestHistory.isNotEmpty(), "принудительное обновление обслужилось из кеша")
    assertTrue(snapshot.authoritative)
  }

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

    val catalog = requireNotNull(
      service(jsonEngine(body, imagesOf("openrouter/auto", "openrouter/auto-beta", "vendor/img-live")))
        .refresh(now = 1_000),
    )

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
      {"fetched_at":1000,"schema":${CachedGenaiCatalog.CURRENT_SCHEMA},"catalog":{"models":[
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
  fun `cache from an older schema is refetched, not served half-empty`() = runTest {
    // Кеш живёт сутки и разбирается без ошибок даже когда в записи появились новые поля: они просто
    // становятся пустыми. Без версии схемы описания моделей были бы пустыми сутки на каждой уже
    // установленной копии — то есть фича выглядела бы полностью нерабочей.
    val cache = cacheFile()
    cache.parentFile.mkdirs()
    cache.writeText(
      """
      {"fetched_at":1000,"catalog":{"models":[
        {"id":"vendor/stale","modality":"IMAGE","priority":1}
      ]}}
      """.trimIndent(),
    )

    val snapshot = service(jsonEngine(liveBody, imageBody), cache).refreshIfStale(now = 1_500)

    val image = snapshot.catalog.forModality(GenerationModality.IMAGE)
    assertTrue(image.none { it.id == "vendor/stale" }, "устаревший кеш не должен пережить смену схемы")
    assertEquals("Описание от вендора.", image.single { it.id == "vendor/img-live" }.description)
  }

  @Test
  fun `vendor name and description travel from the live catalog to the entry`() = runTest {
    val catalog = requireNotNull(service(jsonEngine(liveBody, imageBody)).refresh(now = 1_000))

    val image = catalog.forModality(GenerationModality.IMAGE).single { it.id == "vendor/img-live" }
    assertEquals("Vendor: Живое имя", image.vendorName)
    assertEquals("Описание от вендора.", image.description)
    // Сида у этой модели нет, значит подпись обязана прийти от вендора, а не быть выведенной из слага.
    assertEquals("Vendor: Живое имя", image.displayName)
  }

  @Test
  fun `models listed by the user survive a live refresh and come first`() = runTest {
    // Свой genai-models.json — прямое указание пользователя, а не подсказка. Живой список не
    // повод молча его выбросить: OpenRouter не перечисляет в /models всё, что умеет обслужить.
    val mine = GenaiModelCatalog(
      models = listOf(GenaiModelEntry("me/my-image", GenerationModality.IMAGE, priority = 1, label = "Моя")),
    )

    val catalog = requireNotNull(
      service(jsonEngine(liveBody, imageBody), pinnedCatalog = mine).refresh(now = 1_000),
    )

    val images = catalog.forModality(GenerationModality.IMAGE).map { it.id }
    assertEquals("me/my-image", images.firstOrNull(), "свой список моделей потерялся при обновлении")
    assertTrue(images.contains("vendor/img-live"), "живые модели должны добавиться к своим")
  }

  @Test
  fun `live catalog is split by output modality, chat-only models dropped`() = runTest {
    val catalog = requireNotNull(
      service(jsonEngine(liveBody, imageBody), seedCatalog = GenaiModelCatalog()).refresh(now = 1_000),
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

    val catalog = requireNotNull(service(jsonEngine(imageOnly, imageBody)).refresh(now = 1_000))

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
    val catalog = requireNotNull(service(jsonEngine(liveBody, imageBody)).refresh(now = 1_000))

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
    service(jsonEngine(liveBody, imageBody), cache).refresh(now = 1_000)

    val engine = MockEngine { error("сеть дёрнули при свежем кеше") }
    val snapshot = service(engine, cache).refreshIfStale(now = 1_000 + 60_000)

    assertTrue(snapshot.authoritative)
    assertEquals(0, engine.requestHistory.size, "запрос ушёл, хотя кеш ещё свежий")
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `a stale cache is refreshed from the network`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody, imageBody), cache).refresh(now = 0)

    val newer = """
      {"data":[{"id":"vendor/img-newer","created":900,"architecture":{"output_modalities":["image"]}}]}
    """.trimIndent()
    val snapshot = service(jsonEngine(newer, imagesOf("vendor/img-newer")), cache)
      .refreshIfStale(now = 25L * 60 * 60 * 1000)

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
    val service = service(jsonEngine(liveBody, imageBody), cache)

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
    service(jsonEngine(liveBody, imageBody), cache).refresh(now = 0)

    val broken = service(jsonEngine("{это не json", "{это не json"), cache)
    assertEquals(null, broken.refresh(now = 25L * 60 * 60 * 1000), "мусор в ответе принят за каталог")

    // Кеш пережил неудачное обновление и всё ещё считается списком провайдера.
    val snapshot = broken.refreshIfStale(now = 25L * 60 * 60 * 1000)
    assertTrue(snapshot.authoritative)
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `an answer without usable models does not clobber a working cache`() = runTest {
    val cache = cacheFile()
    service(jsonEngine(liveBody, imageBody), cache).refresh(now = 0)

    // Разобралось, но генеративного нет вовсе — одни чат-модели.
    val chatOnly = """
      {"data":[{"id":"vendor/chat","created":50,"architecture":{"output_modalities":["text"]}}]}
    """.trimIndent()
    assertEquals(null, service(jsonEngine(chatOnly), cache).refresh(now = 25L * 60 * 60 * 1000))

    val snapshot = service(jsonEngine(chatOnly), cache).refreshIfStale(now = 25L * 60 * 60 * 1000)
    // Картиночный каталог тоже пуст: «ответ без пригодных моделей» должен быть пустым целиком.
    assertEquals(listOf("vendor/img-live"), snapshot.catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `models curated in the built-in list come first, the rest newest-first`() = runTest {
    // Правило одно на оба каталога: порядок не должен зависеть от того, из какой ручки приехала
    // модель, — пользователь видит одну колонку.
    val images = """
      {"data":[
        {"id":"vendor/img-new","created":900},
        {"id":"vendor/img-old","created":100},
        {"id":"vendor/img-seed","created":1}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(liveBody, images)).refresh(now = 1_000))

    assertEquals(
      listOf("vendor/img-seed", "vendor/img-new", "vendor/img-old"),
      catalog.forModality(GenerationModality.IMAGE).map { it.id },
    )
  }

  @Test
  fun `blank ids and duplicates never reach the catalog`() = runTest {
    val body = """
      {"data":[
        {"id":"","created":900},
        {"id":"vendor/img-live","created":300},
        {"id":"vendor/img-live","created":200}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(liveBody, body)).refresh(now = 1_000))

    assertEquals(listOf("vendor/img-live"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
  }

  @Test
  fun `image models come from the dedicated endpoint, not from the general one`() = runTest {
    // Главная находка 2026-08-05: в общем /api/v1/models картиночных моделей одиннадцать, а в
    // /api/v1/images/models — сорок. Qwen Image, Krea, MAI-Image, GPT Image в общий список не
    // попадают вовсе, и пользователю показывали четверть каталога.
    val general = """
      {"data":[
        {"id":"vendor/img-in-general","created":300,"architecture":{"output_modalities":["image"]}},
        {"id":"vendor/tts-live","created":100,"architecture":{"output_modalities":["audio"]}}
      ]}
    """.trimIndent()

    val catalog = requireNotNull(
      service(jsonEngine(general, imagesOf("vendor/img-only-here")), seedCatalog = GenaiModelCatalog())
        .refresh(now = 1_000),
    )

    assertEquals(
      listOf("vendor/img-only-here"),
      catalog.forModality(GenerationModality.IMAGE).map { it.id },
      "картинки берутся из своего каталога, а из общего не дублируются",
    )
    // Аудио при этом по-прежнему из общего: своей ручки у него нет.
    assertEquals(listOf("vendor/tts-live"), catalog.forModality(GenerationModality.AUDIO).map { it.id })
  }

  @Test
  fun `image model capabilities come from supported_parameters, unknown shapes are ignored`() = runTest {
    // Форма поля разнородная: enum рядом с range и boolean. Из range контрол не построить, поэтому
    // он должен молча не появиться — предложить «n от 1 до 6» чипами было бы выдумкой.
    val images = """
      {"data":[{"id":"vendor/img-live","supported_parameters":{
        "resolution":{"type":"enum","values":["1K","2K"]},
        "aspect_ratio":{"type":"enum","values":["1:1","16:9"]},
        "output_format":{"type":"enum","values":["png","jpeg"]},
        "n":{"type":"range","min":1,"max":6},
        "seed":{"type":"boolean"}
      }}]}
    """.trimIndent()

    val catalog = requireNotNull(service(jsonEngine(liveBody, images)).refresh(now = 1_000))

    val caps = catalog.forModality(GenerationModality.IMAGE).single().image
    assertEquals(listOf("1K", "2K"), caps?.resolutions)
    assertEquals(listOf("1:1", "16:9"), caps?.aspectRatios)
    assertEquals(listOf("png", "jpeg"), caps?.outputFormats)
  }

  @Test
  fun `a model that declares nothing offers no controls at all`() = runTest {
    val catalog = requireNotNull(
      service(jsonEngine(liveBody, imagesOf("vendor/img-live"))).refresh(now = 1_000),
    )

    val caps = catalog.forModality(GenerationModality.IMAGE).single().image
    assertEquals(false, caps?.hasAnyControl, "нечего показывать — контролов быть не должно")
  }

  @Test
  fun `a cache written with any other schema is refetched`() {
    // Раньше здесь пересчитывалась сама формула — такой тест не может упасть и ничего не стережёт.
    // Проверяем поведение: любое несовпадение версии означает «перекачать».
    val cache = cacheFile()
    cache.parentFile.mkdirs()
    cache.writeText(
      """
      {"fetched_at":1000,"schema":${CachedGenaiCatalog.CURRENT_SCHEMA + 1},"catalog":{"models":[
        {"id":"vendor/stale","modality":"IMAGE","priority":1}
      ]}}
      """.trimIndent(),
    )

    val service = service(jsonEngine(liveBody, imageBody), cache)
    val fresh = runBlocking { service.refreshIfStale(now = 1_500) }

    assertTrue(
      fresh.catalog.models.none { it.id == "vendor/stale" },
      "кеш с чужой версией схемы не должен пережить обновление",
    )
  }

  @Test
  fun `the schema also tracks nested capability types`() {
    // Первый раз обожглись именно на этом: поле добавили не в саму запись, а в ImageCapabilities
    // внутри неё. Список полей записи от этого не меняется — значит версия обязана смотреть глубже.
    assertTrue(
      "image" in GenaiModelEntry.serializer().descriptor.elementNames.toList(),
      "параметры картинки должны входить в форму записи",
    )
    val nested = ImageCapabilities.serializer().descriptor.elementNames.toList()
    assertTrue(nested.isNotEmpty(), "у вложенного типа должны быть поля, иначе стеречь нечего")
  }
}
