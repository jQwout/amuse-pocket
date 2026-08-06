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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * требует и денег не стоит, поэтому 2026-08-05 его ответ посмотрели по-настоящему — и он оказался
 * НЕ тем источником для генерации, каким считался. В общем списке 338 моделей, из них с картинкой на
 * выходе всего 11 (девять настоящих плюс два маршрутизатора `openrouter/auto*`, лезущих в самый верх
 * по дате), 4 аудио и ни одной видео. А у картинок и видео есть свои каталоги: в
 * `/api/v1/images/models` **сорок** моделей, в `/api/v1/videos/models` — 21. Qwen Image, Krea,
 * MAI-Image, GPT Image в общий список не попадают вовсе, то есть пользователю показывали четверть
 * доступного и заподозрить это было неоткуда. Поэтому картинки и видео берутся только из своих ручек,
 * а общий `/models` остался за аудио. Обе находки прямо отражены в [assemble] и в фильтре вендора. Генерация же (`/chat/completions`,
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

  /** Одновременных запросов за ценой не больше шести: моделей сорок, а хост чужой. */
  private val pricingGate = Semaphore(MAX_PARALLEL_PRICING)

  /**
   * Каталог для UI: свежий кеш, обновление по сети, старый кеш или сид — в этом порядке.
   * У вызывающего всегда есть что показать, поэтому возвращается не nullable.
   *
   * [force] пропускает суточный TTL и всегда идёт в сеть — так каталог тянется на каждый запуск
   * приложения. Кеш при этом не лишний, а меняет роль: по нему рисуется первый кадр, пока сеть
   * отвечает, и он же остаётся единственным списком, когда сети нет вовсе.
   */
  suspend fun refreshIfStale(
    now: Long = System.currentTimeMillis(),
    force: Boolean = false,
  ): GenaiCatalogSnapshot = withContext(Dispatchers.IO) {
    // Весь метод на IO, а не только чтение кеша: assemble() и seed() тоже читают файлы с диска
    // (свой список моделей, встроенный ресурс), а зовут этот метод с Dispatchers.Default.
    val cached = readCache()
    if (!force && cached != null && now - cached.fetchedAt < TTL_MS) {
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
      val images = fetchImageModels()
      val live = toCatalog(json.decodeFromString<GenaiModelsResponse>(response.body<String>()).data)
        .let { GenaiModelCatalog(it.models + images + fetchVideoModels()) }
      // Ответ разобрался, но полезного в нём нет: перезаписывать рабочий кеш пустотой нельзя.
      if (live.models.isEmpty()) return@withContext null
      // Кешируем только ПОЛНЫЙ ответ. Одна моргнувшая ручка картинок — и на сутки закреплялся
      // каталог, где картиночных моделей три (из сида) вместо сорока и ни у одной нет параметров,
      // то есть блоки «Формат сторон» и «Качество» исчезали целиком. Снаружи это неотличимо от
      // «фича сломалась», а вылечить можно было только кнопкой «Обновить каталог».
      // Отдаём собранное сейчас, но в кеш не пишем: следующий запуск попробует снова.
      if (images.isEmpty()) return@withContext assemble(live)
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
      // Картинки и видео берём ТОЛЬКО из их собственных каталогов, а общий /models оставляем
      // аудио. Причина не в аккуратности, а в полноте: в общем списке картиночных моделей
      // одиннадцать, а в /api/v1/images/models — сорок. Qwen Image, Krea, MAI-Image, GPT Image
      // в общий список не попадают вовсе, и пользователь просто не видел большей части каталога.
      .filterNot { (_, modality) ->
        modality == GenerationModality.VIDEO || modality == GenerationModality.IMAGE
      }
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
            vendorName = model.name,
            description = model.description,
            voices = fromSeed?.voices.orEmpty(),
            created = model.created,
          )
        }
      }
    return GenaiModelCatalog(entries)
  }

  /**
   * Картинки живут за отдельным `GET /api/v1/images/models`, и это не дубль общего списка: там их
   * **сорок** против одиннадцати в `/api/v1/models` (проверено на живом API 2026-08-05). Qwen Image,
   * Krea, MAI-Image, GPT Image в общий каталог не попадают вовсе — то есть пользователю показывали
   * четверть доступных моделей и повода заподозрить это не было.
   *
   * Отказ эндпоинта не роняет каталог: пустой список, и модальность откатится на встроенные
   * подсказки в [assemble] — та же схема, что у видео.
   */
  private suspend fun fetchImageModels(): List<GenaiModelEntry> = try {
    val response = httpClient.get(IMAGE_MODELS_URL)
    if (!response.status.isSuccess()) {
      emptyList()
    } else {
      val seedCatalog = seed()
      val seedById = seedCatalog.models.associateBy { it.id }
      val seedOrder = seedCatalog.models.map { it.id }
      json.decodeFromString<ImageModelsResponse>(response.body<String>()).data
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .filterNot { it.id.substringBefore('/') == ROUTER_VENDOR }
        // Порядок тот же, что у общего каталога: сперва выверенное руками, потом новые сверху.
        // Держать здесь другое правило значило бы, что порядок списка зависит от того, из какой
        // ручки модель приехала, — а пользователю видна одна общая колонка.
        .sortedWith(
          compareBy<ImageModelDto> {
            seedOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
          }.thenByDescending { it.created },
        )
        .mapIndexed { index, dto ->
          GenaiModelEntry(
            id = dto.id,
            modality = GenerationModality.IMAGE,
            priority = index + 1,
            label = seedById[dto.id]?.label.orEmpty(),
            vendorName = dto.name,
            description = dto.description,
            created = dto.created,
            image = dto.capabilities(),
          )
        }
        .withPricing()
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyList()
  }

  /**
   * Дозапрашивает цену каждой модели: она живёт не в каталоге, а за отдельной ручкой на модель.
   *
   * Сорок моделей — сорок запросов, поэтому гейт: без него открытие приложения било бы по чужому
   * хосту сорока параллельными соединениями. Ровно та же мера и по той же причине, что в
   * [ModelPreviews].
   *
   * Отказ ручки цены НЕ роняет ни модель, ни каталог: у записи просто не будет прайса, а окно
   * выбора скажет «цены нет» вместо числа. Ради этого и не заведено правила «не кешировать каталог
   * без цен», хотя для картинок такое правило есть: там пустой ответ отнимал у пользователя три
   * четверти списка, здесь же модель остаётся на месте и выбирается как прежде. Плюс каталог
   * тянется на каждый запуск ([refreshIfStale] с `force`), так что вчерашняя неудача сама
   * рассасывается при следующем открытии, а не живёт сутки.
   */
  private suspend fun List<GenaiModelEntry>.withPricing(): List<GenaiModelEntry> = coroutineScope {
    map { entry -> async { entry.copy(pricing = pricingGate.withPermit { fetchPricing(entry.id) }) } }
      .awaitAll()
  }

  /**
   * Цена вывода одной модели из `/api/v1/images/models/{id}/endpoints`.
   *
   * Провайдеров у модели бывает несколько (у `gemini-3-pro-image` это Vertex и AI Studio), и цены у
   * них совпадают не по правилу, а по факту. Берём ПЕРВЫЙ, который вообще назвал цену вывода:
   * порядок в ответе — это порядок предпочтения самого OpenRouter, то есть тот, по которому запрос
   * скорее всего и уйдёт. Показывать в списке два числа за одну модель значило бы задавать
   * пользователю вопрос, на который он не может ответить: маршрут выбирает не он.
   */
  private suspend fun fetchPricing(modelId: String): List<ImagePrice> = try {
    val response = httpClient.get("$IMAGE_MODELS_URL/$modelId/endpoints")
    if (!response.status.isSuccess()) {
      emptyList()
    } else {
      json.decodeFromString<ModelEndpointsResponse>(response.body<String>())
        .endpoints
        .map { endpoint -> endpoint.pricing.filter { it.billable == OUTPUT_IMAGE && it.unit.isNotBlank() } }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .map { ImagePrice(unit = it.unit, costUsd = it.costUsd, variant = it.variant.orEmpty()) }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyList()
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
            vendorName = dto.name,
            description = dto.description,
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
    }.getOrNull()
      // Кеш прошлой версии схемы читается без ошибок, но с пустыми новыми полями — для нас это то же
      // самое, что его нет: пусть перекачается, а не показывает сутки полупустые записи.
      ?.takeIf { it.schema == CachedGenaiCatalog.CURRENT_SCHEMA }
      ?.takeIf { it.catalog.models.isNotEmpty() }
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
      tmp.writeText(json.encodeToString(CachedGenaiCatalog(fetchedAt, catalog, CachedGenaiCatalog.CURRENT_SCHEMA)))
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
    const val IMAGE_MODELS_URL = "https://openrouter.ai/api/v1/images/models"
    const val VIDEO_MODELS_URL = "https://openrouter.ai/api/v1/videos/models"

    const val FIRST_FRAME = "first_frame"
    const val LAST_FRAME = "last_frame"
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val REQUEST_TIMEOUT_MS = 20_000L

    /** Строка прайса, которая нас касается: сколько стоит то, что модель ОТДАЁТ. */
    const val OUTPUT_IMAGE = "output_image"
    const val MAX_PARALLEL_PRICING = 6

    /** Сутки — как в :app: каталог меняется неделями, а не часами. */
    const val TTL_MS = 24 * 60 * 60 * 1000L

    const val IMAGE_MODALITY = "image"
    const val VIDEO_MODALITY = "video"
    const val AUDIO_MODALITY = "audio"

    /** Вендор маршрутизирующих псевдомоделей (openrouter/auto): выбор и цена непредсказуемы. */
    const val ROUTER_VENDOR = "openrouter"
  }
}

/**
 * Каталог, скачанный с OpenRouter, вместе со временем скачивания (для TTL).
 *
 * [schema] — не украшение. Кеш живёт сутки, и когда в записи каталога добавляется поле (так было с
 * `vendor_name`/`description`), старый файл продолжает разбираться без ошибок: недостающие поля
 * молча становятся пустыми. То есть новая возможность на сутки выглядела бы полностью нерабочей —
 * на каждой уже установленной копии. Несовпадение версии означает «перекачать», а не «сломалось».
 */
@Serializable
internal data class CachedGenaiCatalog(
  val fetchedAt: Long,
  val catalog: GenaiModelCatalog,
  val schema: Int = 0,
) {
  companion object {
    /**
     * Версия формы записи каталога — ВЫВОДИТСЯ из самой записи, а не пишется руками.
     *
     * Была константа с наказом «поднимать при каждом новом поле». Наказ не сработал ровно один раз
     * и сразу: в [GenaiModelEntry] добавилось поле `image` (параметры картиночной модели), константа
     * осталась прежней — и приложение сутки отдавало кеш, где у всех сорока моделей это поле пустое.
     * Снаружи это выглядело как «фича не работает», а не как «кеш старый», и найти это можно было
     * только заглянув в файл кеша.
     *
     * Список имён полей у сериализатора меняется сам, стоит добавить, убрать или переименовать поле.
     * Значит несовпадение версии теперь наступает тогда же, когда меняется форма данных, — без
     * участия человека и без шанса забыть.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val CURRENT_SCHEMA: Int = shapeOf(GenaiModelEntry.serializer().descriptor).hashCode()

    /**
     * Форма записи ВМЕСТЕ С ВЛОЖЕННЫМИ типами.
     *
     * Только своих полей мало: `image` — это `ImageCapabilities`, и новое поле ВНУТРИ него список
     * полей самой записи не меняет. То есть первая же добавленная возможность модели снова
     * приезжала бы сутки пустой — ровно та беда, ради которой версия и выводится. А следующее поле
     * почти наверняка окажется именно там.
     *
     * Один уровень вложенности: глубже у нас ничего нет, а полный обход требовал бы защиты от
     * циклов ради задачи, которой она не нужна.
     */
    private fun shapeOf(descriptor: SerialDescriptor): String =
      descriptor.elementNames.joinToString(",") { name ->
        val child = descriptor.getElementDescriptor(descriptor.getElementIndex(name))
        val nested = runCatching { child.elementNames.joinToString("+") }.getOrDefault("")
        if (nested.isEmpty()) name else "$name($nested)"
      }
  }
}

// --- DTO ответа /api/v1/models: только используемые поля, остальное съедает ignoreUnknownKeys ---

@Serializable
internal data class GenaiModelsResponse(
  val data: List<GenaiCatalogModel> = emptyList(),
)

@Serializable
internal data class GenaiCatalogModel(
  val id: String = "",
  /**
   * Имя от вендора: «Google: Nano Banana 2 (Gemini 3.1 Flash Image)». Приходит у всех записей.
   * Раньше выбрасывалось, и подпись выводилась из слага своими руками — то есть делалась хуже
   * готовой: из `gemini-3.1-flash-image` никакими правилами не получить «Nano Banana 2».
   */
  val name: String = "",
  /**
   * Описание модели, пара предложений от вендора. Тоже приходит у всех и тоже выбрасывалось.
   * В списке выбора это единственное, чем один `gemini-*-image` отличается от соседнего.
   */
  val description: String = "",
  /** Unix-время появления модели — по нему сортируем «новые выше». */
  val created: Long = 0,
  val architecture: GenaiArchitecture = GenaiArchitecture(),
)

// --- DTO ответа /api/v1/videos/models. Поля приходят null у моделей, которые их не заявляют, —
// поэтому здесь nullable-списки, а не пустые по умолчанию: «не заявлено» и «пусто» это одно и то
// же для UI, но разное для отладки, и подменять одно другим на уровне DTO незачем.

/**
 * Ответ `/api/v1/images/models`. Полей там больше (`supported_parameters` с разрешениями и
 * соотношениями сторон, `endpoints`), но берём пока только то, что показываем: добавить параметры
 * картинки — отдельная работа, а не побочный эффект расширения списка.
 */
@Serializable
internal data class ImageModelsResponse(
  val data: List<ImageModelDto> = emptyList(),
)

@Serializable
internal data class ImageModelDto(
  val id: String = "",
  val name: String = "",
  val description: String = "",
  /** Unix-время появления модели — по нему «новые выше», как в общем каталоге. */
  val created: Long = 0,
  /**
   * Что модель принимает. Форма у поля разнородная — `{"type":"enum","values":[…]}` рядом с
   * `{"type":"range","min":…}` и `{"type":"boolean"}`, — поэтому это сырой JSON, а не типизованная
   * структура: описывать типами то, из чего берутся три списка строк, дороже, чем оно стоит.
   */
  val supportedParameters: JsonObject = JsonObject(emptyMap()),
) {
  fun capabilities() = ImageCapabilities(
    resolutions = supportedParameters.enumValues("resolution"),
    aspectRatios = supportedParameters.enumValues("aspect_ratio"),
    outputFormats = supportedParameters.enumValues("output_format"),
    qualities = supportedParameters.enumValues("quality"),
    maxReferences = supportedParameters.rangeMax("input_references"),
  )
}

/**
 * Значения enum-параметра. Пусто, если параметра нет или он не enum, — а пусто для UI означает
 * «не рисовать контрол», то есть отсутствующий параметр и параметр незнакомой формы обрабатываются
 * одинаково безопасно: мы не предложим того, чего модель не заявляла.
 */
private fun JsonObject.enumValues(name: String): List<String> {
  val spec = this[name] as? JsonObject ?: return emptyList()
  if ((spec["type"] as? JsonPrimitive)?.contentOrNull != "enum") return emptyList()
  val values = spec["values"] as? JsonArray ?: return emptyList()
  return values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/**
 * Верхняя граница range-параметра (`input_references`, `n`). 0 — параметра нет, он другой формы или
 * граница не число.
 *
 * Отдельно от [enumValues], потому что форма другая: `{"type":"range","min":0,"max":14}`. Ноль здесь
 * означает ровно «не заявлено» и для UI неотличим от отсутствия — то же правило, что у enum-списков:
 * не предлагать того, чего модель о себе не говорила.
 */
private fun JsonObject.rangeMax(name: String): Int {
  val spec = this[name] as? JsonObject ?: return 0
  if ((spec["type"] as? JsonPrimitive)?.contentOrNull != "range") return 0
  return (spec["max"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
}

/**
 * Ответ `/api/v1/images/models/{id}/endpoints` — единственное место, где у картиночной модели есть
 * цена. В самом каталоге её нет, а в общем `/api/v1/models` она только за токен и только у
 * одиннадцати моделей из сорока.
 */
@Serializable
internal data class ModelEndpointsResponse(
  val endpoints: List<ModelEndpointDto> = emptyList(),
)

@Serializable
internal data class ModelEndpointDto(
  val providerSlug: String = "",
  /** Пустой список — провайдер цену не назвал. Так отвечает, например, `krea`. */
  val pricing: List<EndpointPriceDto> = emptyList(),
)

@Serializable
internal data class EndpointPriceDto(
  /** `output_image`, `input_image`, `input_text` — что считают. Нас касается только вывод. */
  val billable: String = "",
  /** `image`, `megapixel`, `token`. Пустая строка — форма ответа поменялась, такую цену не берём. */
  val unit: String = "",
  val costUsd: Double = 0.0,
  /** `1k`/`2k`/`4k` у тех, кто берёт за тиры разное. null — цена одна на все. */
  val variant: String? = null,
)

@Serializable
internal data class VideoModelsResponse(
  val data: List<VideoModelDto> = emptyList(),
)

@Serializable
internal data class VideoModelDto(
  val id: String = "",
  val name: String = "",
  val description: String = "",
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
