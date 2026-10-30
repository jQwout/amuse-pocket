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
 * The Meta Model API catalog is `config/models_meta.json`, static (see [ModelCatalogLoader.loadMeta]):
 * the app has no live model list.
 */
@Serializable
data class ModelCatalog(
  val baseUrl: String = META_BASE_URL,
  val models: List<ModelEntry> = emptyList(),
) {
  companion object {
    const val META_BASE_URL = "https://api.meta.ai/v1"
  }
}

@Serializable
data class ModelEntry(
  /** Model id as the Meta Model API names it, e.g. `muse-spark-1.3`. */
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
   * What the model accepts as input: `text`, `image`, `file`, ...
   *
   * Not used in THIS phase — a gate for future attachments: the paperclip and the camera may be shown
   * only where the model can really accept them, otherwise the user attaches a photo and gets silently
   * ignored. We start storing it right away so the format does not have to change later. An empty list
   * means "unknown", NOT "text only".
   */
  val inputModalities: List<String> = emptyList(),
  /**
   * Whether the model can call tools .
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
 * neither in the choice nor in the chain. And it can get there silently: a vendor lists it next to
 * the regular version, with tools and the same name, only with a suffix.
 */
fun trainsOnUserData(modelId: String): Boolean = modelId.endsWith(CONTRIBUTOR_SUFFIX)

private const val CONTRIBUTOR_SUFFIX = "-contributor"

@SingleIn(AppScope::class)
@Inject
class ModelCatalogLoader(
  private val application: Application,
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
   * The Meta Model API catalog — static, from the assets, without a live list.
   *
   * Meta does have `GET /v1/models`, but there is no reason to fetch it: it has four models with tools,
   * and their order is not "as the server returned it" but curated — Muse Wonder is 3–5 times the price
   * of Spark and comes last, only as a fallback. Nothing here changes by itself, and a new Meta model
   * needs its price checked anyway (see metaListPriceUsd).
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
  }
}
