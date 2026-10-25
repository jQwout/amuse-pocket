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
 * Живой список моделей OpenRouter: `GET /api/v1/models` → [OpenRouterCatalogCache].
 *
 * Зачем вообще ходить в сеть, если есть ассет: у OpenRouter каталог меняется каждую неделю (модели
 * появляются, дешевеют и снимаются), и вшитый список — это список, который однажды начнёт отдавать
 * 404 на каждую попытку. Ассет остаётся сидом на первый запуск и офлайн, не источником правды.
 *
 * Эндпоинт публичный — ключ не нужен: список можно показать ещё до того, как пользователь его
 * введёт. Любая ошибка сети здесь не громкая: остаётся предыдущий кеш (или сид), а пользователь
 * просто видит список чуть старее.
 */
@SingleIn(AppScope::class)
@Inject
class OpenRouterCatalogService(
  private val cache: OpenRouterCatalogCache,
  private val modelCatalogLoader: ModelCatalogLoader,
  // Движок инъектится — как у OpenAiCompatClient, чтобы тест подменял транспорт MockEngine'ом.
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
   * Обновляет каталог, если кеша нет или он старше [TTL_MS]. Возвращает актуальный каталог —
   * свежескачанный, кешированный или сид: у вызывающего всегда есть что показать.
   *
   * [CatalogSnapshot.authoritative] отличает «это реальный список провайдера» (сеть или кеш) от
   * «это сид из ассетов». Разница не косметическая: по неавторитетному списку нельзя судить, что
   * модель снята с обслуживания, — а Настройки на таком выводе сбрасывают выбор пользователя.
   */
  suspend fun refreshIfStale(now: Long = System.currentTimeMillis()): CatalogSnapshot {
    // Чтение кеша — это чтение файла с диска, поэтому оно тоже на IO: вызывающий (SettingsViewModel)
    // живёт на главном потоке, а каталог OpenRouter это сотни моделей, не пять строк ассета.
    val cached = withContext(Dispatchers.IO) { cache.read() }
    if (cached != null && now - cached.fetchedAt < TTL_MS) return CatalogSnapshot(cached.catalog, authoritative = true)
    refresh(now)?.let { return CatalogSnapshot(it, authoritative = true) }
    cached?.let { return CatalogSnapshot(it.catalog, authoritative = true) }
    return CatalogSnapshot(
      withContext(Dispatchers.IO) { modelCatalogLoader.loadOpenRouterSeed() },
      authoritative = false,
    )
  }

  /** Скачивает и кеширует каталог. null — сходить не удалось (сеть, 5xx, мусор в ответе). */
  suspend fun refresh(now: Long = System.currentTimeMillis()): ModelCatalog? = withContext(Dispatchers.IO) {
    try {
      val response = httpClient.get(MODELS_URL)
      // Статус проверяем явно: у Ktor expectSuccess по умолчанию выключен, и тело ошибки
      // (`{"error":{...}}`) разобралось бы в пустой список — то есть 429 выглядел бы в логе как
      // «не нашлось моделей», а не как «нас ограничили».
      if (!response.status.isSuccess()) {
        Log.w(TAG, "OpenRouter catalog refresh failed: HTTP ${response.status.value}")
        return@withContext null
      }
      val models = json.decodeFromString<OpenRouterModelsResponse>(response.body<String>()).data
      val catalog = toCatalog(models)
      if (catalog.models.isEmpty()) {
        // Ответ разобрался, но полезного в нём нет: перезаписывать рабочий кеш пустотой нельзя.
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
   * Отбор и порядок.
   *
   * Отбор — только модели с `tools`: без function calling агентский луп, MCP-тулы и план-движок
   * просто перестают работать, и выглядит это как «агент отупел», а не как неверный выбор модели.
   * И никаких моделей, на данных которых обучается вендор ([trainsOnUserData]), и только модели Meta
   * ([isMetaModel]): приложение работает на Muse.
   *
   * Порядок — сначала курируемый сид (проверенные вручную модели), потом всё остальное по дате
   * появления, новые выше. Алфавит здесь был бы худшим из вариантов: он поднял бы наверх случайных
   * вендоров на «a».
   */
  private fun toCatalog(models: List<OpenRouterModel>): ModelCatalog {
    val seedOrder = modelCatalogLoader.loadOpenRouterSeed().models.map { it.name }
    val usable = models
      // id — ключ модели везде дальше: в запросе, в настройках, в списке выбора. Пустой id это
      // конфиг, который всегда даст 400 и сожжёт попытку из пяти; дубли — краш LazyColumn на
      // одинаковых ключах, причём записанный в кеш, то есть переживающий перезапуск.
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
          // Потолок ответа общий для всех моделей; если провайдер разрешает меньше — берём его число.
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
    /** Сутки: каталог меняется неделями, а не часами — чаще ходить незачем. */
    const val TTL_MS = 24 * 60 * 60 * 1000L
    const val MAX_TOKENS = 16_000
    const val TOOLS_PARAM = "tools"
    const val REASONING_PARAM = "reasoning"
    const val TEXT_MODALITY = "text"
  }
}

/**
 * Каталог + откуда он взялся. `authoritative = false` означает сид из ассетов: это не список
 * провайдера, а страховка на случай «нет сети и нет кеша».
 */
data class CatalogSnapshot(
  val catalog: ModelCatalog,
  val authoritative: Boolean,
)

// --- DTO ответа /api/v1/models (только те поля, что реально используем; остальное ignoreUnknownKeys) ---

@Serializable
internal data class OpenRouterModelsResponse(
  val data: List<OpenRouterModel> = emptyList(),
)

@Serializable
internal data class OpenRouterModel(
  val id: String = "",
  /** Unix-время появления модели в каталоге — по нему сортируем «новые выше». */
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
