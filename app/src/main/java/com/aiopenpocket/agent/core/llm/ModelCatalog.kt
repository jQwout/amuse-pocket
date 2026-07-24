package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

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
 * Ranked NVIDIA NIM model catalog loaded from `assets/config/models.json`.
 * This file is the single source of truth for which models the agent may use:
 * the model with the lowest [ModelEntry.priority] is tried first, the rest act
 * as fallbacks (handled by [ProviderFallbackChain] + [OpenAiCompatClient]).
 */
@Serializable
data class ModelCatalog(
  val baseUrl: String = DEFAULT_BASE_URL,
  val models: List<ModelEntry> = emptyList(),
) {
  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
  }
}

@Serializable
data class ModelEntry(
  /** NVIDIA NIM model id, e.g. `deepseek-ai/deepseek-v4-flash`. */
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
)

@SingleIn(AppScope::class)
@Inject
class ModelCatalogLoader(
  private val application: Application,
) {
  // base_url / max_tokens in JSON are snake_case; map them to the camelCase DTO fields.
  // namingStrategy всё ещё @ExperimentalSerializationApi — единственная причина opt-in здесь.
  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  /** Loads the catalog from assets, sorted by priority. Falls back to a single safe model on error. */
  fun load(): ModelCatalog {
    val catalog = try {
      application.assets.open(ASSET_PATH).bufferedReader().use {
        json.decodeFromString<ModelCatalog>(it.readText())
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load $ASSET_PATH, using fallback model: ${e.message}")
      return FALLBACK
    }
    if (catalog.models.isEmpty()) {
      Log.w(TAG, "$ASSET_PATH has no models, using fallback model")
      return FALLBACK
    }
    return catalog.copy(models = catalog.models.sortedBy { it.priority })
  }

  private companion object {
    const val TAG = "ModelCatalogLoader"
    const val ASSET_PATH = "config/models.json"
    val FALLBACK = ModelCatalog(
      models = listOf(ModelEntry(name = "meta/llama-3.1-70b-instruct", priority = 1)),
    )
  }
}
