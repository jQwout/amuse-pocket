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
 * Muse Spark watching a short video — currently over the OpenRouter route
 * (`POST /api/v1/chat/completions`, model `meta/muse-spark-1.3`, a `video_url` content part).
 *
 * A separate client rather than attachments in [OpenAiCompatClient]: the agent loop, the tools and the
 * history are built entirely on text messages, while a lesson needs one question and one answer about
 * one clip. Bringing video into the shared loop would mean rebuilding ChatMessage, history
 * serialization and every orchestrator for the sake of one lesson step.
 *
 * The video goes as a base64 data URL: OpenRouter passes a link on to the provider only if it supports
 * one, and a local file on a phone is never a link. No stream: the answer is short, and the user sees
 * "Watching the video…" on the card.
 *
 * Does NOT throw (except on cancellation): any failure is `null` and a line in the log.
 */
class VideoUnderstandingClient(
  engine: HttpClientEngine,
  /** The key is read on every request; an empty one means "do not send" (see LlmModule). */
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = OPENROUTER_CHAT_URL,
  val model: String = MUSE_SPARK_MODEL,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // usage={include:true} is a default value; without encodeDefaults it would silently not be sent and there would be no price.
    encodeDefaults = true
    // And empty fields of the parts (text on the video part, video_url on the text part) are not sent at all.
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      // Video + reasoning before the answer: longer than a normal turn, but not endless.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /** The model's answer to [instruction] about [video], or `null`: no key, network, a refusal, an empty answer. */
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
        // The provider did not name the spend — zeros in the tokens are not a measurement.
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
    // OpenRouter names the price in dollars only when asked — as in OpenAiCompatClient.
    val usage: UsageRequest = UsageRequest(),
  )

  @Serializable
  private data class Message(val role: String, val content: List<Part>)

  @Serializable
  private data class Part(
    val type: String,
    val text: String? = null,
    // The API field name is video_url, like image_url in the OpenAI format.
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
     * The clip ceiling. Ten seconds in low quality is a couple of megabytes; anything larger is either
     * a long video (expensive and slow) or 4K, which the model does not need. Base64 adds another third.
     */
    const val MAX_VIDEO_BYTES = 20 * 1024 * 1024
    private const val MAX_TOKENS = 1500
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 180_000L
  }
}
