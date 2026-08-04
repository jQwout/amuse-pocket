package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

/**
 * Каталог + откуда он взялся. `authoritative = false` означает встроенные подсказки: это не список
 * провайдера, а страховка на случай «нет сети и нет кеша».
 *
 * Разница не косметическая — ровно та же, что у `CatalogSnapshot` в :app: по неавторитетному списку
 * нельзя заключить, что модель снята с обслуживания, и нельзя на этом основании трогать выбор
 * пользователя.
 */
data class GenaiCatalogSnapshot(
  val catalog: GenaiModelCatalog,
  val authoritative: Boolean,
)

/**
 * Живой список генеративных моделей OpenRouter: `GET /api/v1/models` → файл-кеш → встроенный сид.
 *
 * Зачем: до этого каталог генерации был статическим JSON, и README сам признавал, что слаги
 * устаревают быстрее, чем правится файл. В :app эта задача уже решена правильно
 * (`OpenRouterCatalogService`), и здесь взят тот же принцип, а не вторая самодельная схема —
 * это и есть «каталог берём из :app» из плана.
 *
 * Отличие от :app ровно одно, и оно предметное: там модели отбираются по `tools` (без function
 * calling агентский луп бессмыслен), здесь — по **выходной** модальности, потому что генератору
 * важно, что модель отдаёт, а не что она принимает. Чат-модели (только `text` на выходе) в каталог
 * генерации не попадают вовсе.
 *
 * Эндпоинт публичный, ключ не нужен: список можно показать ещё до того, как пользователь введёт
 * ключ. Любая ошибка сети здесь тихая — остаётся кеш или сид, список просто чуть старее.
 *
 * Что здесь проверено на живом API, а что нет. Сам `/api/v1/models` — эндпоинт публичный, ключа не
 * требует и денег не стоит, поэтому 2026-08-04 его ответ посмотрели по-настоящему: 11 картиночных
 * моделей, 4 аудио и **ни одной видео**, плюс маршрутизаторы `openrouter/auto*` в самом верху по
 * дате. Обе находки прямо отражены в [assemble] и в фильтре вендора. Генерация же (`/chat/completions`,
 * `/videos`, `/audio/speech`) на живом ключе по-прежнему НЕ прогонялась — там схемы из документации.
 */
class GenaiCatalogService(
  engine: HttpClientEngine,
  private val cacheFile: File,
  private val seed: () -> GenaiModelCatalog = { GenaiModelCatalog.load() },
  private val pinned: () -> GenaiModelCatalog = { GenaiModelCatalog() },
) : AutoCloseable {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
  }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /**
   * Каталог для UI: свежий кеш, обновление по сети, старый кеш или сид — в этом порядке.
   * У вызывающего всегда есть что показать, поэтому возвращается не nullable.
   */
  suspend fun refreshIfStale(
    now: Long = System.currentTimeMillis(),
  ): GenaiCatalogSnapshot = withContext(Dispatchers.IO) {
    // Весь метод на IO, а не только чтение кеша: assemble() и seed() тоже читают файлы с диска
    // (свой список моделей, встроенный ресурс), а зовут этот метод с Dispatchers.Default.
    val cached = readCache()
    if (cached != null && now - cached.fetchedAt < TTL_MS) {
      return@withContext GenaiCatalogSnapshot(assemble(cached.catalog), authoritative = true)
    }
    refresh(now)?.let { return@withContext GenaiCatalogSnapshot(it, authoritative = true) }
    // Сходить не вышло — старый кеш всё равно список провайдера, просто вчерашний.
    cached?.let { return@withContext GenaiCatalogSnapshot(assemble(it.catalog), authoritative = true) }
    GenaiCatalogSnapshot(seed(), authoritative = false)
  }

  /** Скачивает и кеширует каталог. null — сходить не удалось (сеть, 5xx, мусор в ответе). */
  suspend fun refresh(now: Long = System.currentTimeMillis()): GenaiModelCatalog? = withContext(Dispatchers.IO) {
    try {
      val response = httpClient.get(MODELS_URL)
      // Статус проверяем явно: expectSuccess у Ktor по умолчанию выключен, и тело ошибки
      // разобралось бы в пустой список — 429 выглядел бы как «моделей не нашлось».
      if (!response.status.isSuccess()) return@withContext null
      val live = toCatalog(json.decodeFromString<GenaiModelsResponse>(response.body<String>()).data)
        .let { GenaiModelCatalog(it.models + fetchVideoModels()) }
      // Ответ разобрался, но полезного в нём нет: перезаписывать рабочий кеш пустотой нельзя.
      if (live.models.isEmpty()) return@withContext null
      writeCache(live, now)
      assemble(live)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Отбор — по выходной модальности; чат-модели отсеиваются. Порядок — сначала то, что перечислено
   * во встроенном списке (его правили руками и проверяли), потом всё остальное, новые выше:
   * алфавит поднял бы наверх случайных вендоров на «a».
   *
   * Подпись и голоса берутся из встроенного списка: живой `/models` про голоса TTS не рассказывает,
   * и без этого «Kokoro 82M» превратился бы в «hexgrad/kokoro-82m» без единого голоса в выборе.
   */
  private fun toCatalog(models: List<GenaiCatalogModel>): GenaiModelCatalog {
    val seedCatalog = seed()
    val seedById = seedCatalog.models.associateBy { it.id }
    val seedOrder = seedCatalog.models.map { it.id }

    val usable = models
      // Пустой id — конфиг, который всегда даст 400; дубли — одинаковые ключи в списке выбора.
      .filter { it.id.isNotBlank() }
      .distinctBy { it.id }
      // openrouter/auto и подобные — маршрутизаторы, а не генераторы: какая модель отработает и по
      // какой цене, заранее неизвестно. Живой список отдаёт их как самые новые, поэтому без этого
      // фильтра маршрутизатор становился моделью по умолчанию — проверено на живом /models.
      .filterNot { it.id.substringBefore('/') == ROUTER_VENDOR }
      .mapNotNull { model -> modalityOf(model)?.let { model to it } }
      // Видео берём ТОЛЬКО из отдельного каталога: там у модели есть длительности, разрешения и
      // соотношения, а здесь их нет вовсе — контролы оказались бы пустыми, зато запись с тем же id
      // задвоилась бы. Сегодня общий каталог видео и не отдаёт (проверено), но правило должно быть
      // одно, а не «как повезёт с ответом».
      .filterNot { (_, modality) -> modality == GenerationModality.VIDEO }
      .sortedWith(
        compareBy<Pair<GenaiCatalogModel, GenerationModality>> {
          seedOrder.indexOf(it.first.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }.thenByDescending { it.first.created },
      )

    val entries = usable
      .groupBy { it.second }
      .flatMap { (modality, group) ->
        group.mapIndexed { index, (model, _) ->
          val fromSeed = seedById[model.id]
          GenaiModelEntry(
            id = model.id,
            modality = modality,
            priority = index + 1,
            label = fromSeed?.label.orEmpty(),
            voices = fromSeed?.voices.orEmpty(),
          )
        }
      }
    return GenaiModelCatalog(entries)
  }

  /**
   * Видео живёт за отдельным `GET /api/v1/videos/models`, а в общем `/api/v1/models` его нет вовсе
   * (проверено на живом API 2026-08-04: 11 картиночных моделей, 4 аудио, ноль видео). До этого
   * видео-подсказки держались на сиде — на слагах, вписанных руками.
   *
   * Отказ этого эндпоинта не роняет весь каталог: возвращаем пустой список, и незакрытая
   * модальность откатится на встроенные подсказки в [assemble] — как и раньше.
   */
  private suspend fun fetchVideoModels(): List<GenaiModelEntry> = try {
    val response = httpClient.get(VIDEO_MODELS_URL)
    if (!response.status.isSuccess()) {
      emptyList()
    } else {
      json.decodeFromString<VideoModelsResponse>(response.body<String>()).data
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .mapIndexed { index, dto ->
          GenaiModelEntry(
            id = dto.id,
            modality = GenerationModality.VIDEO,
            priority = index + 1,
            video = VideoCapabilities(
              durations = dto.supportedDurations.orEmpty(),
              resolutions = dto.supportedResolutions.orEmpty(),
              aspectRatios = dto.supportedAspectRatios.orEmpty(),
              supportsFirstFrame = dto.supportedFrameImages.orEmpty().contains(FIRST_FRAME),
              supportsLastFrame = dto.supportedFrameImages.orEmpty().contains(LAST_FRAME),
              generatesAudio = dto.generateAudio == true,
              supportsSeed = dto.seed == true,
            ),
          )
        }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyList()
  }

  /**
   * Модальность по тому, что модель ОТДАЁТ. Порядок ветвей важен: мультимодальная модель, умеющая
   * и картинку, и текст, — для генератора картиночная.
   */
  private fun modalityOf(model: GenaiCatalogModel): GenerationModality? {
    val out = model.architecture.outputModalities
    return when {
      IMAGE_MODALITY in out -> GenerationModality.IMAGE
      VIDEO_MODALITY in out -> GenerationModality.VIDEO
      AUDIO_MODALITY in out -> GenerationModality.AUDIO
      else -> null
    }
  }

  /**
   * Маршрутизатор, а не генератор. Список пользователя (`pinned`) этим фильтром не трогаем: если он
   * вписал `openrouter/auto` руками, это осознанный выбор, а не мусор из живого списка.
   */
  private fun isRouter(entry: GenaiModelEntry): Boolean =
    entry.id.substringBefore('/') == ROUTER_VENDOR

  /**
   * Собирает то, что увидит пользователь: сначала его собственные модели, потом живой список, потом
   * встроенные подсказки — но только по модальностям, которых не закрыл никто выше.
   *
   * Свои модели не выбрасываются никогда. Файл `genai-models.json` — прямое указание пользователя,
   * а не подсказка, и живой список не повод его молча отменить: OpenRouter перечисляет в `/models`
   * не всё, что готов обслужить.
   *
   * Откат на встроенные подсказки — тоже не перестраховка. Проверено на живом `/api/v1/models`
   * 2026-08-04: там 11 картиночных моделей, 4 аудио и **ни одной видео** — видео у OpenRouter
   * живёт за отдельным `POST /videos`. Без этого отката обновление каталога молча отняло бы у
   * пользователя выбор видео-моделей целиком.
   */
  private fun assemble(live: GenaiModelCatalog): GenaiModelCatalog {
    val pinnedModels = pinned().models
    val pinnedIds = pinnedModels.map { it.id }.toSet()
    // Маршрутизаторы отсекаются и здесь, а не только при разборе ответа: кеш переживает обновление
    // приложения, и список, записанный до появления фильтра, иначе ещё сутки подсовывал бы
    // openrouter/auto моделью по умолчанию.
    val liveModels = live.models.filterNot { it.id in pinnedIds || isRouter(it) }

    val covered = (pinnedModels + liveModels).mapTo(mutableSetOf()) { it.modality }
    val fallback = seed().models.filterNot { it.modality in covered || isRouter(it) }

    // Приоритет пересчитываем по позиции внутри модальности: forModality сортирует именно по нему,
    // а склеенные списки принесли каждый свою нумерацию с единицы.
    return GenaiModelCatalog(
      (pinnedModels + liveModels + fallback)
        .groupBy { it.modality }
        .values
        .flatMap { group -> group.mapIndexed { index, entry -> entry.copy(priority = index + 1) } },
    )
  }

  @Synchronized
  private fun readCache(): CachedGenaiCatalog? {
    if (!cacheFile.isFile) return null
    return runCatching {
      json.decodeFromString<CachedGenaiCatalog>(cacheFile.readText())
    }.getOrNull()?.takeIf { it.catalog.models.isNotEmpty() }
  }

  /**
   * Запись через временный файл: прямой `writeText` сначала обрезает файл до нуля, и читатель,
   * попавший в это окно, увидел бы обрубок. `java.nio.file.Files.move` здесь нельзя — модуль держит
   * рамки Android API 24, см. [AppPaths]. `Throwable`, а не `Exception`: кеш моделей не повод
   * ронять процесс.
   *
   * [Synchronized] обязателен, а не «на всякий случай», — ровно по той же причине, что в `JobStore`.
   * Обновление каталога стартует само при запуске окна, а кнопка «Обновить каталог» доступна
   * пользователю в тот же момент: её флаг защищает только от повторного нажатия. Два писателя через
   * один `.tmp` — и второй не находит своего временного файла, уходит в ветку «удалить и
   * переименовать» и сносит уже сохранённый каталог. Это не теория: тест на 32 параллельных
   * обновления падал 3 прогона из 3 с «каталог исчез».
   *
   * Замок на экземпляре, а писателей на файл ровно столько же — сервис в приложении один
   * ([GenaiRuntime]). Второй экземпляр на тот же файл этой защиты не получит.
   */
  @Synchronized
  private fun writeCache(catalog: GenaiModelCatalog, fetchedAt: Long) {
    val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
    try {
      cacheFile.parentFile?.mkdirs()
      tmp.writeText(json.encodeToString(CachedGenaiCatalog(fetchedAt, catalog)))
      if (!tmp.renameTo(cacheFile)) {
        // renameTo не перезаписывает существующий файл на Windows — сносим и повторяем.
        cacheFile.delete()
        if (!tmp.renameTo(cacheFile)) tmp.delete()
      }
    } catch (e: Throwable) {
      runCatching { tmp.delete() }
    }
  }

  override fun close() {
    httpClient.close()
  }

  private companion object {
    const val MODELS_URL = "https://openrouter.ai/api/v1/models"

    /** Видео у OpenRouter живёт за отдельным каталогом — в общем его нет вовсе. */
    const val VIDEO_MODELS_URL = "https://openrouter.ai/api/v1/videos/models"

    const val FIRST_FRAME = "first_frame"
    const val LAST_FRAME = "last_frame"
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val REQUEST_TIMEOUT_MS = 20_000L

    /** Сутки — как в :app: каталог меняется неделями, а не часами. */
    const val TTL_MS = 24 * 60 * 60 * 1000L

    const val IMAGE_MODALITY = "image"
    const val VIDEO_MODALITY = "video"
    const val AUDIO_MODALITY = "audio"

    /** Вендор маршрутизирующих псевдомоделей (openrouter/auto): выбор и цена непредсказуемы. */
    const val ROUTER_VENDOR = "openrouter"
  }
}

/** Каталог, скачанный с OpenRouter, вместе со временем скачивания (для TTL). */
@Serializable
internal data class CachedGenaiCatalog(
  val fetchedAt: Long,
  val catalog: GenaiModelCatalog,
)

// --- DTO ответа /api/v1/models: только используемые поля, остальное съедает ignoreUnknownKeys ---

@Serializable
internal data class GenaiModelsResponse(
  val data: List<GenaiCatalogModel> = emptyList(),
)

@Serializable
internal data class GenaiCatalogModel(
  val id: String = "",
  /** Unix-время появления модели — по нему сортируем «новые выше». */
  val created: Long = 0,
  val architecture: GenaiArchitecture = GenaiArchitecture(),
)

// --- DTO ответа /api/v1/videos/models. Поля приходят null у моделей, которые их не заявляют, —
// поэтому здесь nullable-списки, а не пустые по умолчанию: «не заявлено» и «пусто» это одно и то
// же для UI, но разное для отладки, и подменять одно другим на уровне DTO незачем.

@Serializable
internal data class VideoModelsResponse(
  val data: List<VideoModelDto> = emptyList(),
)

@Serializable
internal data class VideoModelDto(
  val id: String = "",
  val supportedResolutions: List<String>? = null,
  val supportedAspectRatios: List<String>? = null,
  val supportedDurations: List<Int>? = null,
  val supportedFrameImages: List<String>? = null,
  val generateAudio: Boolean? = null,
  val seed: Boolean? = null,
)

@Serializable
internal data class GenaiArchitecture(
  /** `architecture.output_modalities`: что модель отдаёт. Для генератора это главный признак. */
  val outputModalities: List<String> = emptyList(),
)
