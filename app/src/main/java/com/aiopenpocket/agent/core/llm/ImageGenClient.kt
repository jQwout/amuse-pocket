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

/** One generated image: the bytes and their type (`image/png`, `image/webp`, ...), if the provider named it. */
class GeneratedImage(
  val bytes: ByteArray,
  val mediaType: String?,
)

/**
 * Image generation through Muse Image — currently over the OpenRouter route (`POST /api/v1/images`,
 * model `meta/muse-image`).
 *
 * A separate client rather than another branch of [OpenAiCompatClient]: images have no stream, no
 * tools and no model chain — one request, one answer, and a fallback to "another model" here would
 * mean a different illustration style in the middle of a lesson. The address and the model are
 * parameters: the direct Meta Model API (`/v1/images/generations`, `muse-image-1.0`) returns the image
 * in the same `data[].b64_json` shape.
 *
 * Only `model` and `prompt` are sent: OpenRouter declares no supported parameters for
 * `meta/muse-image`, and an unknown parameter at a strict provider means 400 instead of an image. The
 * frame shape is therefore set by the prompt itself (see LessonImageGenerator).
 *
 * Does NOT throw (except on cancellation): an image in a lesson decorates the step, it is not its
 * substance, and a generation failure must let the lesson go on without it. Any failure is `null` and
 * a line in the log.
 */
class ImageGenClient(
  engine: HttpClientEngine,
  /** The key is read on every request: a key entered in Settings takes effect without a restart. */
  private val apiKey: () -> String,
  /**
   * Spend accounting — no default value, for the same reason as in [OpenAiCompatClient]: a forgotten
   * binding must break the graph build, not quietly lose money from the summary.
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
      // Muse Image "reasons" before drawing — the answer takes noticeably longer than with single-pass
      // models. A ceiling is still needed: a hung request would hold the lesson step forever.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /** The image for [prompt], or `null`: no key, network, a provider refusal, an empty or broken answer. */
  suspend fun generate(prompt: String): GeneratedImage? {
    val key = apiKey()
    if (key.isBlank()) {
      // Without a key the request is guaranteed a 401 — do not spend the network and the step's time on it.
      Log.i(TAG, "No OpenRouter key — skipping image generation")
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
   * An image is as much a paid model call as a chat turn, and it belongs next to it in the weekly
   * spend card. The provider is OpenRouter, the only route of this client today.
   */
  private suspend fun recordUsage(usage: ImageUsage?) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.OPENROUTER,
        model = model,
        promptTokens = usage?.promptTokens ?: 0,
        completionTokens = usage?.completionTokens ?: 0,
        costUsd = usage?.cost,
        // The provider did not name the spend — zeros in the tokens are not a measurement, and the summary must know it.
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
