package com.aiopenpocket.agent.core.llm

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Одна сгенерированная картинка: байты и их тип (`image/png`, `image/webp`, ...), если провайдер его назвал. */
class GeneratedImage(
  val bytes: ByteArray,
  val mediaType: String?,
)

/**
 * Генерация картинок через Muse Image — сейчас маршрутом OpenRouter (`POST /api/v1/images`, модель
 * `meta/muse-image`).
 *
 * Отдельный клиент, а не ещё одна ветка [OpenAiCompatClient]: у картинок нет ни стрима, ни тулов,
 * ни цепочки моделей — один запрос, один ответ, и фолбэк на «другую модель» тут означал бы другой
 * стиль иллюстраций посреди урока. Адрес и модель — параметры: прямой Meta Model API
 * (`/v1/images/generations`, `muse-image-1.0`) отдаёт картинку в той же форме `data[].b64_json`.
 *
 * Шлём только `model` и `prompt`: OpenRouter не объявляет у `meta/muse-image` ни одного
 * поддерживаемого параметра, а неизвестный параметр у строгого провайдера — это 400 вместо картинки.
 * Форму кадра поэтому задаёт сам промпт (см. LessonImageGenerator).
 *
 * НЕ бросает (кроме отмены): картинка в уроке — украшение шага, а не его суть, и сбой генерации
 * должен оставлять урок идти дальше без неё. Любая неудача — `null` и строка в логе.
 */
class ImageGenClient(
  engine: HttpClientEngine,
  /** Ключ читается на каждый запрос: введённый в Настройках ключ действует без перезапуска. */
  private val apiKey: () -> String,
  /**
   * Учёт расхода — без значения по умолчанию по той же причине, что в [OpenAiCompatClient]:
   * забытое связывание должно ломать сборку графа, а не тихо терять деньги из сводки.
   */
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = OPENROUTER_IMAGES_URL,
  val model: String = MUSE_IMAGE_MODEL,
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
      // Muse Image «рассуждает» перед отрисовкой — ответ идёт заметно дольше, чем у однопроходных
      // моделей. Потолок нужен всё равно: зависший запрос держал бы шаг урока бесконечно.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /** Картинка по [prompt] или `null`: нет ключа, сеть, отказ провайдера, пустой или битый ответ. */
  suspend fun generate(prompt: String): GeneratedImage? {
    val key = apiKey()
    if (key.isBlank()) {
      // Без ключа запрос гарантированно получит 401 — не тратим на него сеть и время шага. Пустой
      // ключ приходит и тогда, когда провайдером выбран не OpenRouter (см. LlmModule).
      Log.i(TAG, "No OpenRouter key or OpenRouter not selected — skipping image generation")
      return null
    }
    return try {
      val response = httpClient.post(endpoint) {
        header("Authorization", "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ImageRequest(model = model, prompt = prompt)))
      }
      val body = response.bodyAsText()
      if (!response.status.isSuccess()) {
        Log.w(TAG, "Image generation failed: HTTP ${response.status.value} ${body.take(300)}")
        return null
      }
      val parsed = json.decodeFromString<ImageResponse>(body)
      val first = parsed.data.firstOrNull { it.b64Json.isNotBlank() }
      if (first == null) {
        Log.w(TAG, "Image generation returned no image")
        return null
      }
      val bytes = Base64.decode(first.b64Json, Base64.DEFAULT)
      recordUsage(parsed.usage)
      GeneratedImage(bytes, first.mediaType)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Image generation failed: ${e.message}")
      null
    }
  }

  /**
   * Картинка — такой же платный вызов модели, как ход чата, и в карточке расхода за неделю должна
   * стоять рядом с ним. Провайдер — OpenRouter, единственный маршрут этого клиента сегодня.
   */
  private suspend fun recordUsage(usage: ImageUsage?) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.OPENROUTER,
        model = model,
        promptTokens = usage?.promptTokens ?: 0,
        completionTokens = usage?.completionTokens ?: 0,
        costUsd = usage?.cost,
        // Провайдер не назвал расход — нули в токенах это не замер, и сводка должна это знать.
        estimated = usage == null,
        hadTools = false,
      ),
    )
  }

  @Serializable
  private data class ImageRequest(val model: String, val prompt: String)

  @Serializable
  private data class ImageResponse(
    val data: List<ImageData> = emptyList(),
    val usage: ImageUsage? = null,
  )

  @Serializable
  private data class ImageData(
    val b64Json: String = "",
    val mediaType: String? = null,
  )

  @Serializable
  private data class ImageUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cost: Double? = null,
  )

  companion object {
    private const val TAG = "ImageGenClient"
    const val OPENROUTER_IMAGES_URL = "${ModelCatalog.OPENROUTER_BASE_URL}/images"
    const val MUSE_IMAGE_MODEL = "meta/muse-image"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 90_000L
  }
}
