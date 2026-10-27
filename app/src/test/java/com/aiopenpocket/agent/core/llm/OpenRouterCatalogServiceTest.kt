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
import org.junit.Assert.assertFalse
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
 * Parsing the live OpenRouter catalog: what gets into the picker, in what order, and what happens when the
 * network fails.
 *
 * The silent failure modes here are more dangerous than the loud ones: a model without `tools` does not
 * crash, it simply stops calling tools (the agent "gets dumber"), and overwriting the working cache with an
 * empty response would leave the user with an empty picker until the next day.
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
      model("meta/t-with-tools"),
      model("meta/t-no-tools", params = listOf("temperature")),
    ).refresh()

    assertEquals(listOf("meta/t-with-tools"), catalog?.models?.map { it.name })
  }

  /**
   * The app works only on Meta's models. meta-llama/ is not Meta as a provider: Llama on OpenRouter is
   * served by third-party hosts, and the request would go to them.
   */
  @Test
  fun `only meta models reach the catalog`() = runTest {
    val catalog = service(
      model("meta/t-muse"),
      model("openai/gpt-x"),
      model("meta-llama/llama-4"),
    ).refresh()

    assertEquals(listOf("meta/t-muse"), catalog?.models?.map { it.name })
  }

  /** A model that does not take text (for example, pure image-to-image) is useless in a chat. */
  @Test
  fun `models that cannot take text are dropped`() = runTest {
    val catalog = service(
      model("meta/t-text"),
      model("meta/t-image-only", modalities = listOf("image")),
    ).refresh()

    assertEquals(listOf("meta/t-text"), catalog?.models?.map { it.name })
  }

  /** The contributor version comes in the same list, with tools and text — only the suffix tells it apart. */
  @Test
  fun `models that train on user data never reach the catalog`() = runTest {
    val catalog = service(
      model("meta/muse-spark-1.3"),
      model("meta/muse-spark-1.3-contributor"),
    ).refresh()

    assertEquals(listOf("meta/muse-spark-1.3"), catalog?.models?.map { it.name })
  }

  /** The curated seed goes on top; everything else is newest first. */
  @Test
  fun `seeded models come first, the rest by creation date descending`() = runTest {
    val seeded = loader.loadOpenRouterSeed().models.first().name
    val catalog = service(
      model("meta/t-old", created = 100),
      model("meta/t-new", created = 300),
      model(seeded, created = 1),
    ).refresh()

    assertEquals(listOf(seeded, "meta/t-new", "meta/t-old"), catalog?.models?.map { it.name })
    assertEquals(listOf(1, 2, 3), catalog?.models?.map { it.priority })
  }

  @Test
  fun `max tokens is capped, but a lower provider limit wins`() = runTest {
    val catalog = service(
      model("meta/t-huge", maxCompletionTokens = 100_000),
      model("meta/t-small", maxCompletionTokens = 4_000),
      model("meta/t-unknown"),
    ).refresh()

    val byName = catalog!!.models.associate { it.name to it.maxTokens }
    assertEquals(16_000, byName["meta/t-huge"])
    assertEquals(4_000, byName["meta/t-small"])
    assertEquals(16_000, byName["meta/t-unknown"])
  }

  @Test
  fun `reasoning and input modalities are carried over`() = runTest {
    val catalog = service(
      model("meta/t-thinker", params = listOf("tools", "reasoning"), modalities = listOf("text", "image")),
    ).refresh()

    val entry = catalog!!.models.single()
    assertTrue(entry.reasoning)
    assertEquals(listOf("text", "image"), entry.inputModalities)
  }

  /**
   * Duplicate ids crash LazyColumn on equal keys in the picker list, and once written to the cache the
   * crash would survive a restart, and the user could not open the picker to choose another model.
   * An empty id is a config that always gives a 400 and burns one of the chain's five attempts.
   */
  @Test
  fun `duplicate and blank ids are dropped`() = runTest {
    val catalog = service(
      model("meta/t-dup", created = 300),
      model("meta/t-dup", created = 100),
      model(""),
    ).refresh()

    assertEquals(listOf("meta/t-dup"), catalog?.models?.map { it.name })
  }

  /** An OpenRouter error body (`{"error":{...}}`) parses into an empty list — the working cache must survive. */
  @Test
  fun `an HTTP error never touches the cache`() = runTest {
    service(model("meta/t-cached")).refresh(now = 1_000L)

    val rateLimited = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.TooManyRequests) })
    assertNull(rateLimited.refresh(now = 2_000L))
    assertEquals(listOf("meta/t-cached"), loader.loadOpenRouter().models.map { it.name })
  }

  /**
   * A broken cache reads as "no cache" and is NOT deleted. Deleting turned any overlap of a read with a
   * write into a lost catalog: read is called on every agent turn, write from the background refresh.
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
    service(model("meta/t-fresh")).refresh(now = 1_000L)

    assertEquals(1_000L, cache.read()?.fetchedAt)
    assertEquals(listOf("meta/t-fresh"), loader.loadOpenRouter().models.map { it.name })
    assertEquals(ModelCatalog.OPENROUTER_BASE_URL, loader.loadOpenRouter().baseUrl)
  }

  @Test
  fun `network failure leaves the previous catalog alone`() = runTest {
    service(model("meta/t-cached")).refresh(now = 1_000L)

    val failing = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.BadGateway) })
    assertNull(failing.refresh(now = 2_000L))
    assertEquals(listOf("meta/t-cached"), loader.loadOpenRouter().models.map { it.name })
  }

  /** The response parsed, but there is not a single usable model: the working cache must not be overwritten with nothing. */
  @Test
  fun `an all-filtered response does not wipe the cache`() = runTest {
    service(model("meta/t-cached")).refresh(now = 1_000L)

    assertNull(service(model("meta/t-no-tools", params = listOf("temperature"))).refresh(now = 2_000L))
    assertEquals(listOf("meta/t-cached"), loader.loadOpenRouter().models.map { it.name })
  }

  @Test
  fun `fresh cache is served without touching the network`() = runTest {
    service(model("meta/t-cached")).refresh(now = 1_000L)

    val calls = mutableListOf<String>()
    val snapshot = service(model("meta/t-newer"), calls = calls).refreshIfStale(now = 1_000L + 60_000L)

    assertEquals(emptyList<String>(), calls)
    assertEquals(listOf("meta/t-cached"), snapshot.catalog.models.map { it.name })
    assertTrue("кеш — это реальный список провайдера", snapshot.authoritative)
  }

  @Test
  fun `a stale cache is refreshed`() = runTest {
    service(model("meta/t-cached")).refresh(now = 1_000L)

    val dayAndABit = 1_000L + 25 * 60 * 60 * 1000L
    val snapshot = service(model("meta/t-newer")).refreshIfStale(now = dayAndABit)

    assertEquals(listOf("meta/t-newer"), snapshot.catalog.models.map { it.name })
    assertTrue(snapshot.authoritative)
  }

  /** Without a cache and without a network the user must still see a list — the seed from the assets. */
  @Test
  fun `no cache and no network falls back to the seeded catalog`() = runTest {
    val failing = OpenRouterCatalogService(cache, loader, MockEngine { respondError(HttpStatusCode.BadGateway) })

    val snapshot = failing.refreshIfStale(now = 1_000L)

    assertNotNull(snapshot.catalog.models.firstOrNull())
    assertEquals(loader.loadOpenRouterSeed().models.map { it.name }, snapshot.catalog.models.map { it.name })
    // The seed is not the provider's list: it cannot tell that a model was withdrawn, and Settings must not
    // reset the user's choice on that basis.
    assertFalse("сид не авторитетен", snapshot.authoritative)
  }
}
