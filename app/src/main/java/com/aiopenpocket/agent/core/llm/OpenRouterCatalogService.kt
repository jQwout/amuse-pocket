package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.util.Log
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The live OpenRouter model list: `GET /api/v1/models` → [OpenRouterCatalogCache].
 *
 * Why go to the network at all when there is an asset: OpenRouter's catalog changes every week (models
 * appear, get cheaper and are retired), and a bundled list is a list that one day starts returning
 * 404 on every attempt. The asset stays a seed for the first launch and offline use, not the source of
 * truth.
 *
 * The endpoint is public — no key needed: the list can be shown before the user enters one. Any
 * network error here is quiet: the previous cache (or the seed) stays, and the user just sees a
 * slightly older list.
 */
@SingleIn(AppScope::class)
@Inject
class OpenRouterCatalogService(
  private val cache: OpenRouterCatalogCache,
  private val modelCatalogLoader: ModelCatalogLoader,
  // The engine is injected — as in OpenAiCompatClient, so a test can swap the transport for a MockEngine.
  engine: HttpClientEngine,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /**
   * Updates the catalog if there is no cache or it is older than [TTL_MS]. Returns the current catalog
   * — freshly downloaded, cached or the seed: the caller always has something to show.
   *
   * [CatalogSnapshot.authoritative] tells "this is the provider's real list" (network or cache) from
   * "this is the asset seed". The difference is not cosmetic: a non-authoritative list cannot tell
   * that a model was retired — and Settings reset the user's choice based on that conclusion.
   */
  suspend fun refreshIfStale(now: Long = System.currentTimeMillis()): CatalogSnapshot {
    // Reading the cache is reading a file from disk, so it is on IO too: the caller (SettingsViewModel)
    // lives on the main thread, and the OpenRouter catalog is hundreds of models, not five asset lines.
    val cached = withContext(Dispatchers.IO) { cache.read() }
    if (cached != null && now - cached.fetchedAt < TTL_MS) return CatalogSnapshot(cached.catalog, authoritative = true)
    refresh(now)?.let { return CatalogSnapshot(it, authoritative = true) }
    cached?.let { return CatalogSnapshot(it.catalog, authoritative = true) }
    return CatalogSnapshot(
      withContext(Dispatchers.IO) { modelCatalogLoader.loadOpenRouterSeed() },
      authoritative = false,
    )
  }

  /** Downloads and caches the catalog. null — the request failed (network, 5xx, garbage in the response). */
  suspend fun refresh(now: Long = System.currentTimeMillis()): ModelCatalog? = withContext(Dispatchers.IO) {
    try {
      val response = httpClient.get(MODELS_URL)
      // The status is checked explicitly: Ktor's expectSuccess is off by default, and an error body
      // (`{"error":{...}}`) would parse into an empty list — so a 429 would show in the log as "no
      // models found" rather than "we were rate-limited".
      if (!response.status.isSuccess()) {
        Log.w(TAG, "OpenRouter catalog refresh failed: HTTP ${response.status.value}")
        return@withContext null
      }
      val models = json.decodeFromString<OpenRouterModelsResponse>(response.body<String>()).data
      val catalog = toCatalog(models)
      if (catalog.models.isEmpty()) {
        // The response parsed, but has nothing useful: a working cache must not be overwritten with emptiness.
        Log.w(TAG, "OpenRouter returned ${models.size} models, none usable — keeping previous catalog")
        return@withContext null
      }
      cache.write(catalog, now)
      Log.i(TAG, "OpenRouter catalog refreshed: ${catalog.models.size} tool-capable models")
      catalog
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "OpenRouter catalog refresh failed: ${e.message}")
      null
    }
  }

  /**
   * Selection and order.
   *
   * Selection — only models with `tools`: without function calling the agent loop, the MCP tools and
   * the plan engine simply stop working, and it looks like "the agent got dumb", not like a wrong model
   * choice. No models the vendor trains on ([trainsOnUserData]), and only Meta models ([isMetaModel]):
   * the app runs on Muse.
   *
   * Order — the curated seed first (models checked by hand), then everything else by release date,
   * newest on top. Alphabetical order would be the worst option here: it would lift random vendors
   * starting with "a" to the top.
   */
  private fun toCatalog(models: List<OpenRouterModel>): ModelCatalog {
    val seedOrder = modelCatalogLoader.loadOpenRouterSeed().models.map { it.name }
    val usable = models
      // The id is the model's key everywhere downstream: in the request, in settings, in the selection list.
      // An empty id is a config that always gives 400 and burns one of five attempts; duplicates crash
      // LazyColumn on equal keys — and, written to the cache, survive a restart.
      .filter { it.id.isNotBlank() }
      .distinctBy { it.id }
      .filter { it.supportedParameters.contains(TOOLS_PARAM) }
      .filter { it.architecture.inputModalities.contains(TEXT_MODALITY) }
      .filterNot { trainsOnUserData(it.id) }
      .filter { isMetaModel(it.id) }
      .sortedWith(
        compareBy<OpenRouterModel> { seedOrder.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
          .thenByDescending { it.created },
      )
    return ModelCatalog(
      baseUrl = ModelCatalog.OPENROUTER_BASE_URL,
      models = usable.mapIndexed { index, m ->
        ModelEntry(
          name = m.id,
          priority = index + 1,
          // The answer ceiling is shared by all models; if the provider allows less, take its number.
          maxTokens = m.topProvider.maxCompletionTokens?.coerceAtMost(MAX_TOKENS) ?: MAX_TOKENS,
          reasoning = m.supportedParameters.contains(REASONING_PARAM),
          inputModalities = m.architecture.inputModalities,
          supportsTools = true,
        )
      },
    )
  }

  private companion object {
    const val TAG = "OpenRouterCatalog"
    const val MODELS_URL = "${ModelCatalog.OPENROUTER_BASE_URL}/models"
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val REQUEST_TIMEOUT_MS = 20_000L
    /** A day: the catalog changes over weeks, not hours — no reason to check more often. */
    const val TTL_MS = 24 * 60 * 60 * 1000L
    const val MAX_TOKENS = 16_000
    const val TOOLS_PARAM = "tools"
    const val REASONING_PARAM = "reasoning"
    const val TEXT_MODALITY = "text"
  }
}

/**
 * A catalog + where it came from. `authoritative = false` means the asset seed: not the provider's
 * list, but insurance for "no network and no cache".
 */
data class CatalogSnapshot(
  val catalog: ModelCatalog,
  val authoritative: Boolean,
)

// --- DTO of the /api/v1/models response (only the fields we actually use; the rest via ignoreUnknownKeys) ---

@Serializable
internal data class OpenRouterModelsResponse(
  val data: List<OpenRouterModel> = emptyList(),
)

@Serializable
internal data class OpenRouterModel(
  val id: String = "",
  /** Unix time the model appeared in the catalog — used to sort "newest on top". */
  val created: Long = 0,
  val architecture: OpenRouterArchitecture = OpenRouterArchitecture(),
  val topProvider: OpenRouterTopProvider = OpenRouterTopProvider(),
  val supportedParameters: List<String> = emptyList(),
)

@Serializable
internal data class OpenRouterArchitecture(
  val inputModalities: List<String> = emptyList(),
)

@Serializable
internal data class OpenRouterTopProvider(
  val maxCompletionTokens: Int? = null,
)
