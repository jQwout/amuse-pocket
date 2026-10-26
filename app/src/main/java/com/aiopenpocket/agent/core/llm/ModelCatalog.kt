package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import android.util.Log
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Ranked model catalog of the provider.
 * The model with the lowest [ModelEntry.priority] is tried first, the rest act as fallbacks
 * (handled by [ProviderFallbackChain] + [OpenAiCompatClient]).
 *
 * The `config/models_openrouter.json` asset is only a SEED for the first launch and offline use; in
 * practice the list is fetched from OpenRouter's `/api/v1/models` and cached (see
 * [OpenRouterCatalogService]). The direct Meta Model API catalog is `config/models_meta.json`, static
 * (see [ModelCatalogLoader.loadMeta]).
 */
@Serializable
data class ModelCatalog(
  val baseUrl: String = OPENROUTER_BASE_URL,
  val models: List<ModelEntry> = emptyList(),
) {
  companion object {
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val META_BASE_URL = "https://api.meta.ai/v1"
  }
}

@Serializable
data class ModelEntry(
  /**
   * Model id as the provider names it: `meta/muse-spark-1.3` on OpenRouter, `muse-spark-1.3` on the
   * direct Meta Model API.
   */
  val name: String,
  /** Selection order — 1 is tried first, then 2, 3, ... as fallbacks. */
  val priority: Int = Int.MAX_VALUE,
  /** Per-request `max_tokens` ceiling for this model. */
  val maxTokens: Int = 1024,
  /** Whether the model supports a thinking/reasoning mode. */
  val reasoning: Boolean = false,
  /** Optional sampling overrides — omitted from the request when null (provider applies its own default). */
  val temperature: Double? = null,
  val topP: Double? = null,
  /**
   * What the model accepts as input: `text`, `image`, `file`, ... The `architecture.input_modalities`
   * key from OpenRouter's `/api/v1/models` response.
   *
   * Not used in THIS phase — a gate for future attachments: the paperclip and the camera may be shown
   * only where the model can really accept them, otherwise the user attaches a photo and gets silently
   * ignored. We start storing it right away so the format does not have to change later. An empty list
   * means "unknown" (that is the case for the asset seed), NOT "text only".
   */
  val inputModalities: List<String> = emptyList(),
  /**
   * Whether the model can call tools (`tools` in `supported_parameters`).
   *
   * For this app it is not decoration: the agent loop, the MCP tools and the plan engine are all built
   * on function calling. A model without tools does not fail — it simply stops calling tools, and from
   * the outside that looks like "the agent got dumb", not like a wrong setting. So such models are not
   * offered for selection.
   */
  val supportsTools: Boolean = true,
)

/**
 * A model the vendor itself trains on. At Meta these are the `-contributor` versions of Muse Spark:
 * they are 12 times cheaper because the user pays with their content.
 *
 * In an app that carries the user's profile, diary, photos and memory such a model is unacceptable —
 * neither in the choice nor in the chain. And it can get there silently: OpenRouter lists it next to
 * the regular version, with tools and the same name, only with a suffix.
 */
fun trainsOnUserData(modelId: String): Boolean = modelId.endsWith(CONTRIBUTOR_SUFFIX)

private const val CONTRIBUTOR_SUFFIX = "-contributor"

/**
 * A Meta model (the Muse family) — the only kind the app takes from OpenRouter.
 *
 * The course teaches on Muse and talks about Meta's models: an agent that answered with someone else's
 * model would teach one thing and show another. The filter is applied when parsing the live catalog,
 * when building the chain (LlmModule) and in the Settings list — a cache written by a previous version
 * and a model chosen before an update may still be foreign.
 * `meta-llama/` is not included: Llama on OpenRouter is served by third-party hosts, not by Meta.
 */
fun isMetaModel(modelId: String): Boolean = modelId.startsWith(META_PREFIX)

private const val META_PREFIX = "meta/"

@SingleIn(AppScope::class)
@Inject
class ModelCatalogLoader(
  private val application: Application,
  private val openRouterCache: OpenRouterCatalogCache,
) {
  // base_url / max_tokens in JSON are snake_case; map them to the camelCase DTO fields.
  // namingStrategy is still @ExperimentalSerializationApi — the only reason for the opt-in here.
  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  /**
   * The current OpenRouter catalog: downloaded and cached ([OpenRouterCatalogService]), or the asset
   * seed if there is none yet.
   *
   * Read AGAIN on every access, not once when the graph is built: a catalog update must take effect
   * from the very next turn, otherwise the user could choose a model the chain does not know until the
   * process restarts.
   */
  fun loadOpenRouter(): ModelCatalog = openRouterCache.read()?.catalog ?: loadOpenRouterSeed()

  /**
   * The OpenRouter SEED list from the assets: first launch, offline, and the "curated models on top"
   * order when parsing the live answer. It is NOT the source of truth — the downloaded catalog is.
   */
  fun loadOpenRouterSeed(): ModelCatalog = load(OPENROUTER_ASSET_PATH, OPENROUTER_FALLBACK)

  /**
   * The direct Meta Model API catalog — static, from the assets, without a live list.
   *
   * Meta does have `GET /v1/models`, but there is no reason to fetch it: it has four models with tools,
   * and their order is not "as the server returned it" but curated — Muse Wonder is 3–5 times the price
   * of Spark and comes last, only as a fallback. The live OpenRouter catalog exists because it has
   * hundreds of models that change; here nothing changes, and a new Meta model needs its price checked
   * anyway (see metaListPriceUsd).
   */
  fun loadMeta(): ModelCatalog = load(META_ASSET_PATH, META_FALLBACK)

  /** Loads a catalog from assets, sorted by priority. Falls back to a single safe model on error. */
  private fun load(assetPath: String, fallback: ModelCatalog): ModelCatalog {
    val catalog = try {
      application.assets.open(assetPath).bufferedReader().use {
        json.decodeFromString<ModelCatalog>(it.readText())
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load $assetPath, using fallback model: ${e.message}")
      return fallback
    }
    if (catalog.models.isEmpty()) {
      Log.w(TAG, "$assetPath has no models, using fallback model")
      return fallback
    }
    return catalog.copy(models = catalog.models.sortedBy { it.priority })
  }

  private companion object {
    const val TAG = "ModelCatalogLoader"
    const val OPENROUTER_ASSET_PATH = "config/models_openrouter.json"
    const val META_ASSET_PATH = "config/models_meta.json"
    val META_FALLBACK = ModelCatalog(
      baseUrl = ModelCatalog.META_BASE_URL,
      models = listOf(
        ModelEntry(
          name = "muse-spark-1.3",
          priority = 1,
          maxTokens = 16000,
          reasoning = true,
          inputModalities = listOf("text", "image", "video", "audio", "file"),
        ),
      ),
    )
    val OPENROUTER_FALLBACK = ModelCatalog(
      baseUrl = ModelCatalog.OPENROUTER_BASE_URL,
      models = listOf(
        ModelEntry(
          name = "meta/muse-spark-1.3",
          priority = 1,
          maxTokens = 16000,
          inputModalities = listOf("text", "image", "video", "file"),
        ),
      ),
    )
  }
}
