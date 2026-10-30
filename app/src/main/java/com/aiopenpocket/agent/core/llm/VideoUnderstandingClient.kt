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
import kotlin.random.Random

/**
 * Muse Spark watching a short video, on the direct Meta Model API (`POST /v1/chat/completions`, model
 * `muse-spark-1.3`, a `video_url` content part), on the person's own Meta key. Without a key nothing is
 * sent ([isConfigured] is false) and [describe] returns `null`.
 *
 * A separate client rather than attachments in [OpenAiCompatClient]: the agent loop, the tools and the
 * history are built entirely on text messages, while a lesson needs one question and one answer about
 * one clip. Bringing video into the shared loop would mean rebuilding ChatMessage, history
 * serialization and every orchestrator for the sake of one lesson step.
 *
 * The video goes as a base64 data URL: a local file on a phone is never a link. Meta accepts MP4 only
 * and 50 MB inline; our own ceiling ([MAX_VIDEO_BYTES]) is far below that. No stream: the answer is
 * short, and the user sees "Watching the video…" on the card.
 *
 * The request holds only fields Meta documents for this endpoint: `model`, `messages` and
 * `max_completion_tokens` (`max_tokens` is a deprecated alias; reasoning tokens count against the limit,
 * so it is generous). Meta answers 400 to unsupported parameters, and OpenRouter's `usage: {include}`
 * is not one it knows - never sent. 429 and 503 are retried with Meta's backoff ([RetryBackoff]).
 *
 * Does NOT throw (except on cancellation): any failure is `null` and a line in the log.
 *
 * **Spend.** Meta's usage block carries tokens, never a price, so the call is priced from them with
 * [metaListPriceUsd] (Spark: $1.25 in / $4.25 out / $0.15 cached per 1M). No usage at all: zeros, no
 * price, and the row says `estimated`.
 */
class VideoUnderstandingClient(
  engine: HttpClientEngine,
  /** The key is read on every request; an empty one means "do not send" (see LlmModule). */
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = META_CHAT_URL,
  val model: String = MUSE_SPARK_MODEL,
  private val random: Random = Random.Default,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
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

  /** Whether a key is set — lets a caller say "add a Meta key" instead of a bare "unavailable". */
  fun isConfigured(): Boolean = apiKey().isNotBlank()

  /** The model's answer to [instruction] about [video], or `null`: no key, network, a refusal, an empty answer. */
  suspend fun describe(instruction: String, video: ByteArray, mimeType: String): String? {
    val key = apiKey()
    if (key.isBlank()) {
      Log.i(TAG, "No Meta key for video — skipping")
      return null
    }
    if (!mimeType.equals(MP4_MIME_TYPE, ignoreCase = true)) {
      // Meta takes MP4 only; any other type is a guaranteed 400 on a request of many megabytes.
      Log.w(TAG, "Not an MP4 ($mimeType) — skipping")
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
        maxCompletionTokens = MAX_COMPLETION_TOKENS,
      )
      val requestBody = json.encodeToString(request)
      val response = RetryBackoff.send(random, TAG) {
        httpClient.post(endpoint) {
          header("Authorization", "Bearer $key")
          contentType(ContentType.Application.Json)
          setBody(requestBody)
        }
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

  private suspend fun recordUsage(usage: SseUsage?) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = model,
        promptTokens = usage?.promptTokens ?: 0,
        completionTokens = usage?.completionTokens ?: 0,
        // Meta names tokens, not money: the price comes from the list (see metaListPriceUsd).
        costUsd = usage?.let { metaListPriceUsd(model, it) },
        // The provider named no usage - zeros in the tokens are not a measurement.
        estimated = usage == null,
        hadTools = false,
      ),
    )
  }

  @Serializable
  private data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val maxCompletionTokens: Int,
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
  private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: SseUsage? = null,
  )

  @Serializable
  private data class Choice(val message: ResponseMessage? = null)

  @Serializable
  private data class ResponseMessage(val content: String? = null)

  companion object {
    private const val TAG = "VideoUnderstanding"
    const val META_CHAT_URL = "${ModelCatalog.META_BASE_URL}/chat/completions"
    const val MUSE_SPARK_MODEL = "muse-spark-1.3"
    /**
     * The clip ceiling. Ten seconds in low quality is a couple of megabytes; anything larger is either
     * a long video (expensive and slow) or 4K, which the model does not need. Base64 adds another third.
     */
    const val MAX_VIDEO_BYTES = 20 * 1024 * 1024
    const val MP4_MIME_TYPE = "video/mp4"

    /**
     * The answer is short, but the model reasons first and those tokens count against the limit: with
     * a tight cap the visible answer comes back empty.
     */
    private const val MAX_COMPLETION_TOKENS = 4000
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 180_000L
  }
}
