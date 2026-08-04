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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Единственный провайдер на все три модальности: OpenRouter покрывает картинки, видео и TTS
 * одним ключом и одним биллингом (проверено 2026-07-29).
 *
 * Три разных протокола за одним интерфейсом:
 *  - картинки — `POST /chat/completions` с `modalities: ["image","text"]`, ответ синхронный;
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

  // --- Картинки: синхронный chat/completions -------------------------------------------------

  private suspend fun kotlinx.coroutines.flow.FlowCollector<GenerationProgress>.generateImage(
    request: GenerationRequest,
    key: String,
  ) {
    emit(GenerationProgress.Accepted())
    // Путь разворачивается в data:-URL здесь, а не в модели запроса: в историю на диске должен
    // попасть путь, а не мегабайты base64.
    val inputImage = request.inputImage?.let { resolveInputImage(it) }
    val content: JsonElement = if (inputImage == null) {
      JsonPrimitive(request.prompt)
    } else {
      // image-to-image: контент становится массивом частей, текст + ссылка на входную картинку.
      buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
        add(
          buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", inputImage) })
          },
        )
      }
    }
    val body = json.encodeToString(
      ChatRequest(model = request.model, messages = listOf(ChatMessageDto("user", content))),
    )
    val response = httpClient.post("$baseUrl/chat/completions") {
      authorized(key)
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    response.ensureSuccess()
    val dto = json.decodeFromString<ChatResponseDto>(response.bodyAsText())
    val images = dto.choices.firstOrNull()?.message?.images.orEmpty()
    if (images.isEmpty()) {
      // HTTP 200 без картинки — это не результат, а молчание модели (та же логика, что «пустой
      // ответ ответом не считается» в OpenAiCompatClient). Текст ответа кладём в сообщение об
      // ошибке: модели, отказавшие по контент-политике, объясняются именно там.
      val said = dto.choices.firstOrNull()?.message?.content?.take(300)?.takeIf { it.isNotBlank() }
      emit(
        GenerationProgress.Failed(
          "Модель не вернула картинку" + (said?.let { ": $it" } ?: ""),
          code = "no_image",
        ),
      )
      return
    }
    val outputs = images.mapNotNull { it.imageUrl?.url?.let(::parseImageOutput) }
    if (outputs.isEmpty()) {
      emit(GenerationProgress.Failed("Картинка пришла в неизвестном формате", code = "bad_image"))
      return
    }
    emit(
      GenerationProgress.Completed(
        GenerationResult(outputs = outputs, costUsd = dto.usage?.cost, modelUsed = dto.model),
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
      request.lastFrameImage?.let {
        add(FrameImageDto(imageUrl = ImageUrlDto(resolveInputImage(it)), frameType = LAST_FRAME))
      }
    }
    val body = json.encodeToString(
      VideoRequest(
        model = request.model,
        prompt = request.prompt,
        duration = request.durationSeconds,
        resolution = request.resolution,
        aspectRatio = request.aspectRatio,
        frameImages = frames.ifEmpty { null },
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

@Serializable
private data class ChatRequest(
  val model: String,
  val messages: List<ChatMessageDto>,
  val modalities: List<String> = listOf("image", "text"),
)

@Serializable
private data class ChatMessageDto(val role: String, val content: JsonElement)

@Serializable
private data class ChatResponseDto(
  val model: String? = null,
  val choices: List<ChoiceDto> = emptyList(),
  val usage: UsageDto? = null,
)

@Serializable
private data class ChoiceDto(val message: ResponseMessageDto? = null)

@Serializable
private data class ResponseMessageDto(
  val content: String? = null,
  val images: List<ResponseImageDto> = emptyList(),
)

@Serializable
private data class ResponseImageDto(val type: String? = null, val imageUrl: ImageUrlDto? = null)

@Serializable
private data class ImageUrlDto(val url: String)

@Serializable
private data class UsageDto(val cost: Double? = null, val totalTokens: Int? = null)

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
