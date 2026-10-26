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
 * Ассет `config/models_openrouter.json` — лишь СИД на первый запуск и офлайн; в бою список тянется
 * с `/api/v1/models` OpenRouter и кешируется (см. [OpenRouterCatalogService]). Каталог прямого Meta
 * Model API — `config/models_meta.json`, статичный (см. [ModelCatalogLoader.loadMeta]).
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
   * Что модель принимает на вход: `text`, `image`, `file`, ... Ключ `architecture.input_modalities`
   * из ответа OpenRouter `/api/v1/models`.
   *
   * В ЭТОЙ фазе не используется — гейт для будущих вложений: скрепку и камеру можно показывать
   * только там, где модель действительно умеет их принять, иначе пользователь прикрепит фото и
   * получит молчаливое игнорирование. Хранить начинаем сразу, чтобы не переделывать формат потом.
   * Пустой список означает «неизвестно» (так у сида из ассетов), НЕ «только текст».
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

/**
 * Модель, на данных которой обучается сам вендор. У Meta это `-contributor`-версии Muse Spark: они в
 * 12 раз дешевле, потому что пользователь платит своим контентом.
 *
 * В приложении, через которое идут профиль, дневник, фото и память, такая модель недопустима — ни в
 * выборе, ни в цепочке. И попасть туда она может молча: OpenRouter отдаёт её в общем списке рядом с
 * обычной версией, с tools и тем же именем, только с суффиксом.
 */
fun trainsOnUserData(modelId: String): Boolean = modelId.endsWith(CONTRIBUTOR_SUFFIX)

private const val CONTRIBUTOR_SUFFIX = "-contributor"

/**
 * Модель Meta (семейство Muse) — только такие приложение и берёт с OpenRouter.
 *
 * Курс учит на Muse и рассказывает про модели Meta: агент, который при этом отвечает чужой моделью,
 * учил бы одному, а показывал другое. Отсев стоит и в разборе живого каталога, и при сборке цепочки
 * (LlmModule), и в списке Настроек — кеш, записанный прошлой версией, и модель, выбранная до
 * обновления, ещё могут быть чужими.
 * `meta-llama/` сюда не входит: Llama на OpenRouter обслуживают сторонние хостеры, а не Meta.
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
  // namingStrategy всё ещё @ExperimentalSerializationApi — единственная причина opt-in здесь.
  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

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

  /**
   * Каталог прямого Meta Model API — статичный, из ассетов, без живого списка.
   *
   * `GET /v1/models` у Meta есть, но тянуть его незачем: моделей с tools там четыре, и порядок у них
   * не «как отдал сервер», а курируемый — Muse Wonder в 3–5 раз дороже Spark и стоит последней, только
   * как фолбэк. Живой каталог OpenRouter нужен потому, что там сотни моделей и они меняются; здесь
   * меняться нечему, а новая модель Meta всё равно требует сверки цены (см. metaListPriceUsd).
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
