package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Единственный провайдер на все три модальности: OpenRouter покрывает картинки, видео и TTS
 * одним ключом и одним биллингом (проверено 2026-07-29).
 *
 * Три разных протокола за одним интерфейсом:
 *  - картинки — `POST /api/v1/images`, ответ синхронный (base64 в `data[].b64_json`);
 *  - видео — `POST /videos` отдаёт 202 + `polling_url`, результат забирается опросом;
 *  - аудио — `POST /audio/speech` отдаёт сырые байты, а не JSON.
 *
 * Движок инъектится, а не создаётся внутри: иначе ни один из трёх протоколов нельзя покрыть
 * тестом без сети (тот же приём, что в `OpenAiCompatClient` у :app).
 */
class OpenRouterProvider(
  engine: HttpClientEngine,
  private val apiKey: () -> String,
  private val baseUrl: String = DEFAULT_BASE_URL,
  /** Интервал опроса видео-задания. Тесты передают 0, чтобы не ждать реальные секунды. */
  private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
  private val maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
) : GenerationProvider, AutoCloseable {

  override val id: String = "openrouter"

  private val json = openRouterJson()

  private val httpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      // Видео-модель думает минутами, но это ожидание размазано по коротким опросам,
      // поэтому таймаут одного запроса держим конечным.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
      socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  override fun close() = httpClient.close()

  override fun supports(modality: GenerationModality): Boolean = true

  override suspend fun checkAccess(): AccessCheck {
    val key = apiKey()
    if (key.isBlank()) {
      return AccessCheck.Failed("Ключ OpenRouter не задан", code = "blank_key")
    }
    return try {
      val response = httpClient.get("$baseUrl/key") { authorized(key) }
      response.ensureSuccess()
      // Ответ приходит завёрнутым в "data"; разворачиваем, но и плоский вариант принимаем —
      // проверка связи не должна падать из-за формы конверта.
      val root = json.decodeFromString<JsonObject>(response.bodyAsText())
      val payload = (root["data"] as? JsonObject) ?: root
      val info = json.decodeFromJsonElement<KeyInfoDto>(payload)
      AccessCheck.Ok(
        label = info.label,
        usageUsd = info.usage,
        limitUsd = info.limit,
        freeTier = info.isFreeTier,
        rateLimit = info.rateLimit?.let { "${it.requests ?: "?"} запросов / ${it.interval ?: "?"}" },
      )
    } catch (e: OpenRouterHttpException) {
      AccessCheck.Failed(e.userMessage, code = e.status.toString())
    } catch (e: Exception) {
      AccessCheck.Failed(e.message ?: "не удалось связаться с OpenRouter")
    }
  }

  override fun generate(request: GenerationRequest): Flow<GenerationProgress> = flow {
    val key = apiKey()
    if (key.isBlank()) {
      emit(GenerationProgress.Failed("Ключ OpenRouter не задан — укажи его в настройках", code = "blank_key"))
      return@flow
    }
    try {
      when (request.modality) {
        GenerationModality.IMAGE -> generateImage(request, key)
        GenerationModality.AUDIO -> generateSpeech(request, key)
        GenerationModality.VIDEO -> generateVideo(request, key)
      }
    } catch (e: OpenRouterHttpException) {
      emit(GenerationProgress.Failed(e.userMessage, code = e.status.toString()))
    } catch (e: Exception) {
      emit(GenerationProgress.Failed(e.message ?: e::class.simpleName ?: "неизвестная ошибка"))
    }
  }

  override fun resume(pollingUrl: String): Flow<GenerationProgress> = flow {
    val key = apiKey()
    if (key.isBlank()) {
      emit(GenerationProgress.Failed("Ключ OpenRouter не задан — укажи его в настройках", code = "blank_key"))
      return@flow
    }
    try {
      pollUntilDone(pollingUrl, key)
    } catch (e: OpenRouterHttpException) {
      emit(GenerationProgress.Failed(e.userMessage, code = e.status.toString()))
    } catch (e: Exception) {
      emit(GenerationProgress.Failed(e.message ?: "неизвестная ошибка"))
    }
  }

  // --- Картинки: собственная ручка POST /api/v1/images ---------------------------------------

  /**
   * Картинки уходят в `POST /api/v1/images`, а НЕ в `chat/completions`.
   *
   * Так было не всегда, и переезд не косметический. Через `chat/completions` картинку получить
   * можно, но в этом запросе некуда положить ни разрешение, ни соотношение сторон, ни формат файла:
   * у чата таких полей нет. Модель их заявляет (`/api/v1/images/models` → `supported_parameters`),
   * пользователь их просит, а класть было некуда — и контролы пришлось бы рисовать декоративными.
   *
   * Вторая причина весомее: каталог картиночных моделей — это `/api/v1/images/models`, там сорок
   * моделей, и через чат из них отвечает меньшинство. Пока список брался из общего `/models`,
   * несовпадения не было видно; после расширения списка выбранная `qwen/qwen-image-3-pro` уходила
   * бы в ручку, которая её не знает, — то есть отказ за деньги на ровном месте.
   *
   * Приятное следствие: вложения теперь уходят настоящим полем `input_references`, а не картинками
   * внутри сообщения чата. Роль и теги по-прежнему словами в промпте — поля для них нет и здесь.
   */
  private suspend fun kotlinx.coroutines.flow.FlowCollector<GenerationProgress>.generateImage(
    request: GenerationRequest,
    key: String,
  ) {
    emit(GenerationProgress.Accepted())
    // Путь разворачивается в data:-URL здесь, а не в модели запроса: в историю на диске должен
    // попасть путь, а не мегабайты base64.
    val inputs = buildList {
      request.inputImage?.let { add(InputAttachment(it, AttachmentRole.REFERENCE)) }
      addAll(request.attachments)
    }
    val references = inputs.map { attachment ->
      ImageReferenceDto(imageUrl = ImageUrlDto(resolveInputImage(attachment.path)))
    }

    val body = json.encodeToString(
      ImageRequestDto(
        model = request.model,
        prompt = describeAttachments(request.prompt, inputs),
        resolution = request.resolution,
        aspectRatio = request.aspectRatio,
        outputFormat = request.outputFormat,
        quality = request.quality,
        inputReferences = references.ifEmpty { null },
      ),
    )
    val response = httpClient.post("$baseUrl/images") {
      authorized(key)
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    response.ensureSuccess()
    val raw = response.bodyAsText()

    // Строгий разбор — быстрый путь. Если он споткнулся, картинку из тела ВЫТАСКИВАЕМ РУКАМИ.
    //
    // Причина не гипотетическая. 2026-08-05 живой ответ пришёл с `"total_tokens":13680.64` —
    // дробным числом в поле, которое нам не нужно вовсе. Разбор падал целиком, а в том же теле
    // лежали и готовая картинка, и `"cost":0.075`. Пользователь заплатил и не получил ничего,
    // увидев вместо кадра текст ошибки парсера.
    //
    // Отсюда правило: ответ за деньги нельзя терять из-за поля, которое мы не читаем. Схема у
    // провайдера меняется без предупреждения, и следующий сюрприз будет в другом поле.
    val dto = runCatching { json.decodeFromString<ImageResponseDto>(raw) }.getOrNull()
    val outputs = dto?.data?.mapNotNull { it.toOutput() }?.takeIf { it.isNotEmpty() }
      ?: salvageImages(raw)

    if (outputs.isEmpty()) {
      // HTTP 200 без картинки — это не результат, а молчание модели (та же логика, что «пустой
      // ответ ответом не считается» в OpenAiCompatClient).
      emit(GenerationProgress.Failed("Модель не вернула картинку", code = "no_image"))
      return
    }
    emit(
      GenerationProgress.Completed(
        GenerationResult(
          outputs = outputs,
          // Цену тоже вытаскиваем руками, если разбор не удался: показать «—» там, где деньги
          // списаны, значит занизить сводку расходов.
          costUsd = dto?.usage?.cost ?: salvageCost(raw),
          modelUsed = request.model,
        ),
      ),
    )
  }

  // --- Аудио: сырые байты в ответе -----------------------------------------------------------

  private suspend fun kotlinx.coroutines.flow.FlowCollector<GenerationProgress>.generateSpeech(
    request: GenerationRequest,
    key: String,
  ) {
    emit(GenerationProgress.Accepted())
    val body = json.encodeToString(
      SpeechRequest(model = request.model, input = request.prompt, voice = request.voice),
    )
    val response = httpClient.post("$baseUrl/audio/speech") {
      authorized(key)
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    response.ensureSuccess()
    // Тело — поток mp3, а не JSON: читаем байтами, минуя ContentNegotiation.
    val bytes = response.readBytes()
    if (bytes.isEmpty()) {
      emit(GenerationProgress.Failed("Провайдер вернул пустое аудио", code = "empty_audio"))
      return
    }
    emit(
      GenerationProgress.Completed(
        GenerationResult(
          outputs = listOf(GenerationOutput.Bytes(bytes, MIME_MP3)),
          modelUsed = request.model,
        ),
      ),
    )
  }

  // --- Видео: асинхронное задание + поллинг --------------------------------------------------

  private suspend fun kotlinx.coroutines.flow.FlowCollector<GenerationProgress>.generateVideo(
    request: GenerationRequest,
    key: String,
  ) {
    // frame_images собираем только из того, что реально задано: пустой массив это не «кадров нет»,
    // а «вот тебе image-to-video без картинок», и провайдер вправе ответить на это отказом.
    val frames = buildList {
      request.inputImage?.let {
        add(FrameImageDto(imageUrl = ImageUrlDto(resolveInputImage(it)), frameType = FIRST_FRAME))
      }
      for (a in request.attachments.filter { it.role == AttachmentRole.FIRST_FRAME }) {
        add(FrameImageDto(imageUrl = ImageUrlDto(resolveInputImage(a.path)), frameType = FIRST_FRAME))
      }
      for (a in request.attachments.filter { it.role == AttachmentRole.LAST_FRAME }) {
        add(FrameImageDto(imageUrl = ImageUrlDto(resolveInputImage(a.path)), frameType = LAST_FRAME))
      }
    }
    // Остальные роли у видео — это input_references: отдельное поле API, не кадры.
    val references = request.attachments
      .filterNot { it.role == AttachmentRole.FIRST_FRAME || it.role == AttachmentRole.LAST_FRAME }
      .map { ImageRefDto(imageUrl = ImageUrlDto(resolveInputImage(it.path))) }
    val body = json.encodeToString(
      VideoRequest(
        model = request.model,
        prompt = request.prompt,
        duration = request.durationSeconds,
        resolution = request.resolution,
        aspectRatio = request.aspectRatio,
        frameImages = frames.ifEmpty { null },
        inputReferences = references.ifEmpty { null },
      ),
    )
    val response = httpClient.post("$baseUrl/videos") {
      authorized(key)
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    response.ensureSuccess()
    val accepted = json.decodeFromString<VideoJobDto>(response.bodyAsText())
    // polling_url приходит абсолютным; собственный путь на случай, если провайдер его не отдал.
    val pollingUrl = accepted.pollingUrl
      ?: accepted.id?.let { "$baseUrl/videos/$it" }
      ?: throw IllegalStateException("Провайдер не вернул ни polling_url, ни id задания")
    emit(GenerationProgress.Accepted(providerJobId = accepted.id, pollingUrl = pollingUrl))
    pollUntilDone(pollingUrl, key)
  }

  private suspend fun kotlinx.coroutines.flow.FlowCollector<GenerationProgress>.pollUntilDone(
    pollingUrl: String,
    key: String,
  ) {
    // Адрес опроса приходит из ответа провайдера, ложится в jobs.json и переигрывается при каждом
    // запуске (resumeUnfinished). Без проверки хоста один битый или подправленный руками адрес
    // получал бы ключ снова и снова. Отказываемся, а не шлём без заголовка: опрос без авторизации
    // всё равно бесполезен, а тихий запрос на чужой хост — нет.
    if (!isOpenRouterHost(pollingUrl)) {
      emit(GenerationProgress.Failed("Адрес опроса ведёт не на OpenRouter — задание брошено", code = "bad_polling_host"))
      return
    }
    repeat(maxPollAttempts) { attempt ->
      if (attempt > 0) delay(pollIntervalMs)
      val response = httpClient.get(pollingUrl) { authorized(key) }
      response.ensureSuccess()
      val dto = json.decodeFromString<VideoJobDto>(response.bodyAsText())
      when (dto.status?.lowercase()) {
        in SUCCESS_STATUSES -> {
          val urls = dto.unsignedUrls
          if (urls.isEmpty()) {
            emit(GenerationProgress.Failed("Задание готово, но ссылок на видео нет", code = "no_output"))
            return
          }
          emit(
            GenerationProgress.Completed(
              GenerationResult(
                outputs = urls.map { GenerationOutput.RemoteUrl(it, MIME_MP4) },
                costUsd = dto.usage?.cost,
                modelUsed = dto.model,
              ),
            ),
          )
          return
        }

        in FAILURE_STATUSES -> {
          emit(
            GenerationProgress.Failed(
              dto.error?.toString()?.takeIf { it != "null" } ?: "Провайдер отклонил задание (${dto.status})",
              code = dto.status,
            ),
          )
          return
        }

        else -> emit(
          GenerationProgress.Status(
            "ждём провайдера (${dto.status ?: "?"}), опрос ${attempt + 1}/$maxPollAttempts",
          ),
        )
      }
    }
    // НЕ Failed. Видео уже оплачено, polling_url сохранён — а терминальный статус означал бы, что
    // `resumeUnfinished()` задание больше не подхватит (он пропускает терминальные), и единственной
    // кнопкой у пользователя осталась бы «Повторить», то есть заплатить второй раз за то же самое.
    // Задание остаётся в POLLING: план прямо обещает, что поллинг переживает перезапуск (§8), и
    // опрос по сохранённой ссылке бесплатен. Надоело ждать — есть «Отменить», и это выбор
    // пользователя, а не наше решение за него.
    emit(
      GenerationProgress.Status(
        "опрос остановлен после $maxPollAttempts попыток — задание осталось у провайдера, " +
          "продолжим по сохранённой ссылке при следующем запуске",
      ),
    )
  }

  // --- Инфраструктура ------------------------------------------------------------------------

  private fun io.ktor.client.request.HttpRequestBuilder.authorized(key: String) {
    header("Authorization", "Bearer $key")
    // OpenRouter показывает эти поля в статистике аккаунта — без них запросы анонимны в дашборде.
    header("HTTP-Referer", ATTRIBUTION_URL)
    header("X-Title", ATTRIBUTION_TITLE)
  }

  private suspend fun HttpResponse.ensureSuccess() {
    val code = status.value
    if (code in 200..299) return
    throw OpenRouterHttpException(code, bodyAsText())
  }

  companion object {
    const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

    /** Документация советует не частить; 5 с — компромисс между нагрузкой и живостью UI. */
    const val DEFAULT_POLL_INTERVAL_MS = 5_000L

    /** 5 с × 180 ≈ 15 минут — верхняя граница даже для медленных видео-моделей. */
    const val DEFAULT_MAX_POLL_ATTEMPTS = 180

    const val MIME_MP3 = "audio/mpeg"
    const val MIME_MP4 = "video/mp4"

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val REQUEST_TIMEOUT_MS = 180_000L
    private const val ATTRIBUTION_URL = "https://github.com/jQwout/ai-open-pocket-2"
    private const val ATTRIBUTION_TITLE = "AI Open Pocket Desktop"

    const val FIRST_FRAME = "first_frame"
    const val LAST_FRAME = "last_frame"

    private val SUCCESS_STATUSES = setOf("completed", "succeeded", "success")
    private val FAILURE_STATUSES = setOf("failed", "error", "cancelled", "canceled")
  }
}

/**
 * Ошибка провайдера с человекочитаемым текстом: 401/402/429 пользователь должен понимать без
 * похода в логи — это три самых частых отказа OpenRouter (нет ключа, нет денег, лимит).
 */
class OpenRouterHttpException(val status: Int, val body: String) : Exception("HTTP $status: $body") {
  val userMessage: String
    get() = when (status) {
      401, 403 -> "OpenRouter не принял ключ (HTTP $status)"
      402 -> "На балансе OpenRouter недостаточно средств"
      404 -> "Модель или задание не найдены (HTTP 404)"
      429 -> "Лимит запросов OpenRouter — попробуй позже"
      else -> "OpenRouter ответил HTTP $status: ${body.replace("\n", " ").take(300)}"
    }
}

// OpenRouter, как и остальной OpenAI-совместимый мир, говорит snake_case (image_url, unsigned_urls,
// response_format). Настройки те же, что у openAiCompatJson() в :app, чтобы код переезжал без правок.
@OptIn(ExperimentalSerializationApi::class)
internal fun openRouterJson(): Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  explicitNulls = false
  encodeDefaults = true
  namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * Роли и теги вложений уходят СЛОВАМИ в промпт: поля для роли картинки нет ни у `chat/completions`,
 * ни у `/api/v1/images` — сами картинки там едут настоящим `input_references`, а вот чем каждая из
 * них является, сказать нечем. Это подсказка модели, а не гарантия, и UI обязан говорить об этом прямо — иначе «Фон» в
 * интерфейсе выглядит как параметр, который провайдер обещал соблюсти.
 *
 * У видео всё иначе: там роли ложатся на настоящие `frame_images`/`input_references`.
 */
internal fun describeAttachments(prompt: String, images: List<InputAttachment>): String {
  val described = images.filter { it.role != AttachmentRole.REFERENCE || it.tags.isNotEmpty() }
  if (described.isEmpty()) return prompt
  val lines = described.joinToString("; ") { attachment ->
    buildString {
      append(attachment.role.label.lowercase()).append(": ").append(attachment.name)
      if (attachment.tags.isNotEmpty()) append(" (").append(attachment.tags.joinToString(", ")).append(")")
    }
  }
  return prompt + "\n\nИсходники — " + lines + "."
}

/**
 * Достаёт картинки из тела ответа в обход схемы — спасательный путь, когда строгий разбор упал.
 *
 * Ищем ровно два поля, которые и составляют результат: `b64_json` и `url`. Всё остальное в ответе
 * нас не касается, и падать из-за него нельзя: за тело уже заплачено.
 *
 * Регулярка, а не «разобрать как JsonElement и походить по дереву»: дерево строит тот же парсер,
 * который только что и споткнулся. Смысл спасательного пути в том, чтобы не зависеть от него.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun salvageImages(raw: String): List<GenerationOutput> {
  val fromBase64 = B64_FIELD.findAll(raw).mapNotNull { match ->
    val bytes = runCatching { Base64.Mime.decode(match.groupValues[1]) }.getOrNull()
    bytes?.takeIf { it.isNotEmpty() }?.let { GenerationOutput.Bytes(it, "image/png") }
  }.toList()
  if (fromBase64.isNotEmpty()) return fromBase64

  return URL_FIELD.findAll(raw)
    .map { it.groupValues[1] }
    .filter { it.startsWith("http") || it.startsWith("data:") }
    .mapNotNull(::parseImageOutput)
    .toList()
}

/** Цена из тела в обход схемы — по той же причине, что и картинки: она уже списана. */
internal fun salvageCost(raw: String): Double? =
  COST_FIELD.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()

private val B64_FIELD = Regex(""""b64_json"\s*:\s*"([^"]+)"""")
private val URL_FIELD = Regex(""""url"\s*:\s*"([^"]+)"""")
private val COST_FIELD = Regex(""""cost"\s*:\s*([0-9.eE+-]+)""")

/**
 * Разбирает то, что модель положила в `image_url`: обычно `data:image/png;base64,…`, но бывает и
 * простая ссылка. Возвращает null только если содержимое разобрать не удалось.
 *
 * Раньше называлась `decodeDataUrl` и в документации обещала null для обычной ссылки, хотя код
 * возвращал ссылку с жёстко зашитым `image/png` — jpeg сохранялся под именем `.png`.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun parseImageOutput(url: String): GenerationOutput? {
  if (!url.startsWith("data:")) return GenerationOutput.RemoteUrl(url, mimeForRemoteImage(url))
  val comma = url.indexOf(',')
  if (comma < 0) return null
  val meta = url.substring("data:".length, comma)
  if (!meta.endsWith(";base64")) return null
  val mime = meta.removeSuffix(";base64").ifBlank { "image/png" }
  val bytes = try {
    Base64.decode(url.substring(comma + 1))
  } catch (e: IllegalArgumentException) {
    return null
  }
  return GenerationOutput.Bytes(bytes, mime)
}

/** Тип картинки по расширению в ссылке; query и якорь отбрасываем, неизвестное считаем png. */
private fun mimeForRemoteImage(url: String): String {
  val path = url.substringBefore('?').substringBefore('#')
  val guess = mimeByExtension(path.substringAfterLast('/'))
  return if (guess.startsWith("image/")) guess else "image/png"
}

// --- DTO ------------------------------------------------------------------------------------

// --- DTO ручки картинок POST /api/v1/images -------------------------------------------------
//
// null-поля не сериализуются (explicitNulls = false), поэтому «пользователь не выбрал разрешение»
// и «модель его не заявляла» дают одинаковый результат: параметра в запросе просто нет, и провайдер
// применяет своё умолчание. Это честнее, чем выбрать значение за него.

@Serializable
private data class ImageRequestDto(
  val model: String,
  val prompt: String,
  val resolution: String? = null,
  val aspectRatio: String? = null,
  val outputFormat: String? = null,
  val quality: String? = null,
  val inputReferences: List<ImageReferenceDto>? = null,
)

@Serializable
private data class ImageReferenceDto(
  val type: String = "image_url",
  val imageUrl: ImageUrlDto,
)

@Serializable
private data class ImageResponseDto(
  val data: List<ImageDataDto> = emptyList(),
  val usage: UsageDto? = null,
)

@Serializable
private data class ImageDataDto(
  /** Байты картинки в base64. Основной способ доставки у этой ручки. */
  val b64Json: String? = null,
  /** Тип пришедшего файла: `image/png`, `image/webp`, `image/svg+xml`. */
  val mediaType: String? = null,
  /** Часть провайдеров отдаёт ссылку вместо байтов — разбираем оба варианта. */
  val url: String? = null,
) {
  @OptIn(ExperimentalEncodingApi::class)
  fun toOutput(): GenerationOutput? {
    b64Json?.takeIf { it.isNotBlank() }?.let { encoded ->
      // Base64.Mime, а не строгий: переносы строк в теле — законная кодировка, а строгий декодер на
      // них падает. Кадр к этому моменту уже оплачен, и терять его из-за переноса строки нельзя.
      val bytes = runCatching { Base64.Mime.decode(encoded) }.getOrNull()
      // Не разобралось — проваливаемся к ссылке той же записи, а не бросаем её целиком: часть
      // провайдеров кладёт и то и другое.
      if (bytes != null) return GenerationOutput.Bytes(bytes, mediaType ?: "image/png")
    }
    return url?.takeIf { it.isNotBlank() }?.let(::parseImageOutput)
  }
}

@Serializable
private data class ImageUrlDto(val url: String)

@Serializable
private data class UsageDto(
  val cost: Double? = null,
  /**
   * ДРОБНОЕ, а не целое. Выглядит как ошибка в схеме, но так отвечает живой API: у картиночных
   * моделей приходит `"total_tokens":13680.64`. С `Int` разбор ответа падал целиком — вместе с уже
   * оплаченной картинкой и `cost` в том же теле. Хуже случая не придумать: деньги списаны, результат
   * выброшен, а пользователю показана ошибка парсера.
   */
  val totalTokens: Double? = null,
  val promptTokens: Double? = null,
  val completionTokens: Double? = null,
)

@Serializable
private data class SpeechRequest(
  val model: String,
  val input: String,
  val voice: String? = null,
  val responseFormat: String = "mp3",
)

@Serializable
private data class VideoRequest(
  val model: String,
  val prompt: String,
  /** Секунды. Допустимые значения свои у каждой модели — см. `supported_durations` в каталоге. */
  val duration: Int? = null,
  val resolution: String? = null,
  val aspectRatio: String? = null,
  /**
   * Первый и последний кадр. Раньше здесь стояло верхнее поле `image_url`, взятое по аналогии с
   * остальным API, — такого поля у `/videos` нет вовсе. Схема сверена с документацией 2026-08-04:
   * это массив, и у каждой записи обязателен `frame_type`.
   */
  val frameImages: List<FrameImageDto>? = null,
  /** Стилевые референсы: отдельное поле схемы, не кадры. */
  val inputReferences: List<ImageRefDto>? = null,
)

@Serializable
private data class ImageRefDto(
  val type: String = "image_url",
  val imageUrl: ImageUrlDto,
)

@Serializable
private data class FrameImageDto(
  val type: String = "image_url",
  val imageUrl: ImageUrlDto,
  val frameType: String,
)

@Serializable
private data class KeyInfoDto(
  val label: String? = null,
  val usage: Double? = null,
  val limit: Double? = null,
  val isFreeTier: Boolean? = null,
  val rateLimit: RateLimitDto? = null,
)

@Serializable
private data class RateLimitDto(val requests: Int? = null, val interval: String? = null)

@Serializable
private data class VideoJobDto(
  val id: String? = null,
  val generationId: String? = null,
  val pollingUrl: String? = null,
  val status: String? = null,
  val model: String? = null,
  val unsignedUrls: List<String> = emptyList(),
  val usage: UsageDto? = null,
  val error: JsonElement? = null,
)
