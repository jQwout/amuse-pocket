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
 * Ranked model catalog for ONE provider, loaded from assets.
 * The model with the lowest [ModelEntry.priority] is tried first, the rest act as fallbacks
 * (handled by [ProviderFallbackChain] + [OpenAiCompatClient]).
 *
 * Файл на провайдера, а не один общий: у NIM список статичен и правится руками, а у OpenRouter
 * ассет — лишь СИД на первый запуск и офлайн; в бою его список тянется с `/api/v1/models` и
 * кешируется. Складывать источники с разной природой в один файл значило бы притворяться, что они
 * одинаковые.
 */
@Serializable
data class ModelCatalog(
  val baseUrl: String = DEFAULT_BASE_URL,
  val models: List<ModelEntry> = emptyList(),
) {
  companion object {
    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
  }
}

@Serializable
data class ModelEntry(
  /** Model id as the provider names it, e.g. `deepseek-ai/deepseek-v4-flash` or `anthropic/claude-sonnet-5`. */
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
   * Что модель принимает на вход: `text`, `image`, `file`, ... Ключ `architecture.input_modalities`
   * из ответа OpenRouter `/api/v1/models`.
   *
   * В ЭТОЙ фазе не используется — гейт для будущих вложений: скрепку и камеру можно показывать
   * только там, где модель действительно умеет их принять, иначе пользователь прикрепит фото и
   * получит молчаливое игнорирование. Хранить начинаем сразу, чтобы не переделывать формат потом.
   * Пустой список означает «неизвестно» (так у статичного NIM-каталога), НЕ «только текст».
   */
  val inputModalities: List<String> = emptyList(),
  /**
   * Умеет ли модель вызывать инструменты (`tools` в `supported_parameters`).
   *
   * Для этого приложения это не украшение: агентский луп, MCP-тулы и план-движок целиком построены
   * на function calling. Модель без tools не падает — она просто перестаёт вызывать инструменты, и
   * со стороны это выглядит как «агент отупел», а не как неверная настройка. Поэтому такие модели
   * в выбор не попадают.
   */
  val supportsTools: Boolean = true,
)

@SingleIn(AppScope::class)
@Inject
class ModelCatalogLoader(
  private val application: Application,
  private val openRouterCache: OpenRouterCatalogCache,
) {
  // base_url / max_tokens in JSON are snake_case; map them to the camelCase DTO fields.
  // namingStrategy всё ещё @ExperimentalSerializationApi — единственная причина opt-in здесь.
  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  /** Статичный список моделей NIM — правится руками, других источников у него нет. */
  fun loadNim(): ModelCatalog = load(NIM_ASSET_PATH, NIM_FALLBACK)

  /**
   * Актуальный каталог OpenRouter: скачанный и закешированный ([OpenRouterCatalogService]), а если
   * его ещё нет — сид из ассетов.
   *
   * Читается ЗАНОВО на каждом обращении, а не один раз при сборке графа: обновление каталога должно
   * действовать со следующего же хода, иначе пользователь выбрал бы модель, которой цепочка не
   * знает, до перезапуска процесса.
   */
  fun loadOpenRouter(): ModelCatalog = openRouterCache.read()?.catalog ?: loadOpenRouterSeed()

  /**
   * СИД-список OpenRouter из ассетов: первый запуск, офлайн и порядок «курируемые модели сверху»
   * при разборе живого ответа. Источником правды НЕ является — им является скачанный каталог.
   */
  fun loadOpenRouterSeed(): ModelCatalog = load(OPENROUTER_ASSET_PATH, OPENROUTER_FALLBACK)

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
    const val NIM_ASSET_PATH = "config/models.json"
    const val OPENROUTER_ASSET_PATH = "config/models_openrouter.json"
    val NIM_FALLBACK = ModelCatalog(
      models = listOf(ModelEntry(name = "meta/llama-3.1-70b-instruct", priority = 1)),
    )
    val OPENROUTER_FALLBACK = ModelCatalog(
      baseUrl = ModelCatalog.OPENROUTER_BASE_URL,
      models = listOf(
        ModelEntry(
          name = "anthropic/claude-sonnet-5",
          priority = 1,
          maxTokens = 16000,
          inputModalities = listOf("text", "image", "file"),
        ),
      ),
    )
  }
}
