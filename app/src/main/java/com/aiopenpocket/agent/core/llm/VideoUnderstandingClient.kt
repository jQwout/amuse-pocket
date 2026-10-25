package com.aiopenpocket.agent.core.llm

// @trace video-na-vhod-v-uroke-muse-spark-08ccdb

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Разбор короткого видео моделью Muse Spark — сейчас маршрутом OpenRouter
 * (`POST /api/v1/chat/completions`, модель `meta/muse-spark-1.3`, часть контента `video_url`).
 *
 * Отдельный клиент, а не вложения в [OpenAiCompatClient]: агентский луп, тулы и история целиком
 * построены на текстовых сообщениях, а уроку нужен один вопрос и один ответ про один ролик. Тащить
 * видео в общий луп значило бы перестроить ChatMessage, сериализацию истории и все оркестраторы
 * ради одного шага урока.
 *
 * Видео уходит data URL в base64: OpenRouter передаёт провайдеру ссылку, только если тот её
 * поддерживает, а локальный файл с телефона ссылкой не бывает. Без стрима: ответ короткий, а
 * пользователь видит на карточке «Смотрю видео…».
 *
 * НЕ бросает (кроме отмены): любая неудача — `null` и строка в логе.
 */
class VideoUnderstandingClient(
  engine: HttpClientEngine,
  /** Ключ читается на каждый запрос; пустой — «не отправлять» (см. LlmModule). */
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = OPENROUTER_CHAT_URL,
  val model: String = MUSE_SPARK_MODEL,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // usage={include:true} — значение по умолчанию, без encodeDefaults оно молча не уйдёт и цены не будет.
    encodeDefaults = true
    // А пустые поля частей (text у видео, video_url у текста) не шлём вовсе.
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      // Видео + рассуждение перед ответом: дольше обычного хода, но не бесконечно.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /** Ответ модели на [instruction] про [video] или `null`: нет ключа, сеть, отказ, пустой ответ. */
  suspend fun describe(instruction: String, video: ByteArray, mimeType: String): String? {
    val key = apiKey()
    if (key.isBlank()) {
      Log.i(TAG, "No OpenRouter key — skipping video")
      return null
    }
    return try {
      val dataUrl = "data:$mimeType;base64," + Base64.encodeToString(video, Base64.NO_WRAP)
      val request = ChatRequest(
        model = model,
        messages = listOf(
          Message(
            role = "user",
            content = listOf(
              Part(type = "text", text = instruction),
              Part(type = "video_url", videoUrl = VideoUrl(dataUrl)),
            ),
          ),
        ),
        maxTokens = MAX_TOKENS,
      )
      val response = httpClient.post(endpoint) {
        header("Authorization", "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(request))
      }
      val body = response.bodyAsText()
      if (!response.status.isSuccess()) {
        Log.w(TAG, "Video request failed: HTTP ${response.status.value} ${body.take(300)}")
        return null
      }
      val parsed = json.decodeFromString<ChatResponse>(body)
      val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
      if (text.isBlank()) {
        Log.w(TAG, "Video request returned no text")
        return null
      }
      recordUsage(parsed.usage)
      text
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Video request failed: ${e.message}")
      null
    }
  }

  private suspend fun recordUsage(usage: Usage?) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.OPENROUTER,
        model = model,
        promptTokens = usage?.promptTokens ?: 0,
        completionTokens = usage?.completionTokens ?: 0,
        costUsd = usage?.cost,
        // Провайдер не назвал расход — нули в токенах это не замер.
        estimated = usage == null,
        hadTools = false,
      ),
    )
  }

  @Serializable
  private data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val maxTokens: Int,
    // Цену в долларах OpenRouter называет только по просьбе — как в OpenAiCompatClient.
    val usage: UsageRequest = UsageRequest(),
  )

  @Serializable
  private data class Message(val role: String, val content: List<Part>)

  @Serializable
  private data class Part(
    val type: String,
    val text: String? = null,
    // Имя поля в API — video_url, как и у image_url в OpenAI-формате.
    @SerialName("video_url") val videoUrl: VideoUrl? = null,
  )

  @Serializable
  private data class VideoUrl(val url: String)

  @Serializable
  private data class UsageRequest(val include: Boolean = true)

  @Serializable
  private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
  )

  @Serializable
  private data class Choice(val message: ResponseMessage? = null)

  @Serializable
  private data class ResponseMessage(val content: String? = null)

  @Serializable
  private data class Usage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cost: Double? = null,
  )

  companion object {
    private const val TAG = "VideoUnderstanding"
    const val OPENROUTER_CHAT_URL = "${ModelCatalog.OPENROUTER_BASE_URL}/chat/completions"
    const val MUSE_SPARK_MODEL = "meta/muse-spark-1.3"
    /**
     * Потолок ролика. Десять секунд в низком качестве — пара мегабайт; всё, что больше, — либо
     * длинное видео (дорого и долго), либо 4K, которое модели не нужно. В base64 объём растёт ещё на треть.
     */
    const val MAX_VIDEO_BYTES = 20 * 1024 * 1024
    private const val MAX_TOKENS = 1500
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 180_000L
  }
}
