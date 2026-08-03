package com.aiopenpocket.agent.core.llm

import android.app.Application
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Разбор живого каталога OpenRouter: что попадает в выбор, в каком порядке и что происходит, когда
 * сеть отказывает.
 *
 * Молчаливые режимы здесь опаснее громких: модель без `tools` не падает, а просто перестаёт звать
 * инструменты (агент «тупеет»), а перезапись рабочего кеша пустым ответом оставила бы пользователя
 * с пустым селектором до следующих суток.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenRouterCatalogServiceTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private val cache = OpenRouterCatalogCache(application)
  private val loader = ModelCatalogLoader(application, cache)

  @After
  fun tearDown() {
    File(application.filesDir, "openrouter_models.json").delete()
  }

  private fun model(
    id: String,
    created: Long = 0,
    params: List<String> = listOf("tools"),
    modalities: List<String> = listOf("text"),
    maxCompletionTokens: Int? = null,
  ): String {
    val maxField = maxCompletionTokens?.toString() ?: "null"
    return """
      {"id":"$id","created":$created,
       "architecture":{"input_modalities":[${modalities.joinToString { "\"$it\"" }}]},
       "top_provider":{"max_completion_tokens":$maxField},
       "supported_parameters":[${params.joinToString { "\"$it\"" }}]}
    """.trimIndent()
  }

  private fun service(vararg models: String, calls: MutableList<String> = mutableListOf()): OpenRouterCatalogService {
    val engine = MockEngine { request ->
      calls += request.url.toString()
      respond(
        content = """{"data":[${models.joinToString(",")}]}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    return OpenRouterCatalogService(cache, loader, engine)
  }

  @Test
  fun `models without tools support never reach the catalog`() = runTest {
    val catalog = service(
      model("vendor/with-tools"),
      model("vendor/no-tools", params = listOf("temperature")),
    ).refresh()

    assertEquals(listOf("vendor/with-tools"), catalog?.models?.map { it.name })
  }

  /** Модель, не принимающая текст (например, чисто image-to-image), в чате бесполезна. */
  @Test
  fun `models that cannot take text are dropped`() = runTest {
    val catalog = service(
      model("vendor/text"),
      model("vendor/image-only", modalities = listOf("image")),
    ).refresh()

    assertEquals(listOf("vendor/text"), catalog?.models?.map { it.name })
  }

  /** Курируемый сид — сверху; всё остальное — новые выше. */
  @Test
  fun `seeded models come first, the rest by creation date descending`() = runTest {
    val seeded = loader.loadOpenRouterSeed().models.first().name
    val catalog = service(
      model("vendor/old", created = 100),
      model("vendor/new", created = 300),
      model(seeded, created = 1),
    ).refresh()

    assertEquals(listOf(seeded, "vendor/new", "vendor/old"), catalog?.models?.map { it.name })
    assertEquals(listOf(1, 2, 3), catalog?.models?.map { it.priority })
  }

  @Test
  fun `max tokens is capped, but a lower provider limit wins`() = runTest {
    val catalog = service(
      model("vendor/huge", maxCompletionTokens = 100_000),
      model("vendor/small", maxCompletionTokens = 4_000),
      model("vendor/unknown"),
    ).refresh()

    val byName = catalog!!.models.associate { it.name to it.maxTokens }
    assertEquals(16_000, byName["vendor/huge"])
    assertEquals(4_000, byName["vendor/small"])
    assertEquals(16_000, byName["vendor/unknown"])
  }

  @Test
  fun `reasoning and input modalities are carried over`() = runTest {
    val catalog = service(
      model("vendor/thinker", params = listOf("tools", "reasoning"), modalities = listOf("text", "image")),
    ).refresh()

    val entry = catalog!!.models.single()
    assertTrue(entry.reasoning)
    assertEquals(listOf("text", "image"), entry.inputModalities)
  }

  /**
   * Дубли id — краш LazyColumn на одинаковых ключах в списке выбора, причём записанный в кеш: он
   * пережил бы перезапуск, и пользователь не смог бы открыть селектор, чтобы выбрать другую модель.
   * Пустой id — конфиг, который всегда даст 400 и сожжёт одну из пяти попыток цепочки.
   */
  @Test
  fun `duplicate and blank ids are dropped`() = runTest {
    val catalog = service(
      model("vendor/dup", created = 300),
      model("vendor/dup", created = 100),
      model(""),
    ).refresh()

    assertEquals(listOf("vendor/dup"), catalog?.models?.map { it.name })
  }

  /** Тело ошибки OpenRouter (`{"error":{...}}`) разбирается в пустой список — рабочий кеш должен уцелеть. */
  @Test
  fun `an HTTP error never touches the cache`() = runTest {
    service(model("vendor/cached")).refresh(now = 1_000L)

    val rateLimited = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.TooManyRequests) })
    assertNull(rateLimited.refresh(now = 2_000L))
    assertEquals(listOf("vendor/cached"), loader.loadOpenRouter().models.map { it.name })
  }

  /**
   * Битый кеш читается как «кеша нет», а НЕ удаляется. Удаление превращало любое пересечение чтения
   * с записью в потерю каталога: read зовётся на каждом ходе агента, write — из фонового обновления.
   */
  @Test
  fun `a corrupted cache falls back to the seed without deleting the file`() = runTest {
    val file = File(application.filesDir, "openrouter_models.json")
    file.writeText("{ это не JSON")

    assertNull(cache.read())
    assertTrue("файл не должен удаляться при чтении", file.exists())
    assertEquals(loader.loadOpenRouterSeed().models.map { it.name }, loader.loadOpenRouter().models.map { it.name })
  }

  @Test
  fun `a successful refresh is cached and picked up by the loader`() = runTest {
    service(model("vendor/fresh")).refresh(now = 1_000L)

    assertEquals(1_000L, cache.read()?.fetchedAt)
    assertEquals(listOf("vendor/fresh"), loader.loadOpenRouter().models.map { it.name })
    assertEquals(ModelCatalog.OPENROUTER_BASE_URL, loader.loadOpenRouter().baseUrl)
  }

  @Test
  fun `network failure leaves the previous catalog alone`() = runTest {
    service(model("vendor/cached")).refresh(now = 1_000L)

    val failing = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.BadGateway) })
    assertNull(failing.refresh(now = 2_000L))
    assertEquals(listOf("vendor/cached"), loader.loadOpenRouter().models.map { it.name })
  }

  /** Ответ разобрался, но ни одной пригодной модели: перезаписать рабочий кеш пустотой нельзя. */
  @Test
  fun `an all-filtered response does not wipe the cache`() = runTest {
    service(model("vendor/cached")).refresh(now = 1_000L)

    assertNull(service(model("vendor/no-tools", params = listOf("temperature"))).refresh(now = 2_000L))
    assertEquals(listOf("vendor/cached"), loader.loadOpenRouter().models.map { it.name })
  }

  @Test
  fun `fresh cache is served without touching the network`() = runTest {
    service(model("vendor/cached")).refresh(now = 1_000L)

    val calls = mutableListOf<String>()
    val catalog = service(model("vendor/newer"), calls = calls).refreshIfStale(now = 1_000L + 60_000L)

    assertEquals(emptyList<String>(), calls)
    assertEquals(listOf("vendor/cached"), catalog.models.map { it.name })
  }

  @Test
  fun `a stale cache is refreshed`() = runTest {
    service(model("vendor/cached")).refresh(now = 1_000L)

    val dayAndABit = 1_000L + 25 * 60 * 60 * 1000L
    val catalog = service(model("vendor/newer")).refreshIfStale(now = dayAndABit)

    assertEquals(listOf("vendor/newer"), catalog.models.map { it.name })
  }

  /** Без кеша и без сети пользователь всё равно должен увидеть список — сид из ассетов. */
  @Test
  fun `no cache and no network falls back to the seeded catalog`() = runTest {
    val failing = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.BadGateway) })

    val catalog = failing.refreshIfStale(now = 1_000L)

    assertNotNull(catalog.models.firstOrNull())
    assertEquals(loader.loadOpenRouterSeed().models.map { it.name }, catalog.models.map { it.name })
  }
}
