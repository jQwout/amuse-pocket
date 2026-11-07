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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/** One generated image: the bytes and their type (`image/webp`, `image/png`, ...), if it is known. */
class GeneratedImage(
  val bytes: ByteArray,
  val mediaType: String?,
)

/** What one [ImageGenClient.attempt] gave: the image, or null and whether the size was what Meta refused. */
class ImageAttempt(
  val image: GeneratedImage?,
  val rejectedSize: Boolean = false,
)

/**
 * Image generation through Muse Image on the direct Meta Model API
 * (`POST https://api.meta.ai/v1/images/generations`, model `muse-image-1.0`), on the person's own Meta
 * key. Without a key nothing is sent ([isConfigured] is false) and lessons keep the bundled pictures.
 *
 * A separate client rather than another branch of [OpenAiCompatClient]: images have no stream, no
 * tools and no model chain — one request, one answer, and a fallback to "another model" here would
 * mean a different illustration style in the middle of a lesson. The address and the model are
 * parameters.
 *
 * The request carries exactly five fields: `model`, `prompt`, `size` (the 16:9 of the lesson card —
 * it sets the aspect ratio only), `response_format` and `output_format`. Meta answers 400 to any
 * top-level field it does not know, and to `moderation` on this model, so nothing else is ever added.
 * The response names no media type: it is derived from the `output_format` field.
 *
 * 429 and 503 are retried with Meta's backoff ([RetryBackoff]); 500 and every other error are not.
 *
 * Does NOT throw (except on cancellation): an image in a lesson decorates the step, it is not its
 * substance, and a generation failure must let the lesson go on without it. Any failure is `null` and
 * a line in the log.
 *
 * **Spend.** $0.01 per successful image ([museImageCostUsd]), recorded only when an image came back.
 * That is a flat list price, not an estimate, so the row is not marked `estimated`; the token counts
 * come from `usage` when Meta sends it and are zeros otherwise. Not seen on a live bill (no paid call
 * has been made).
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
  private val endpoint: String = META_IMAGES_URL,
  val model: String = MUSE_IMAGE_MODEL,
  private val random: Random = Random.Default,
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

  /** Whether a key is set — lets a caller say "add a Meta key" instead of a bare "unavailable". */
  fun isConfigured(): Boolean = apiKey().isNotBlank()

  /** The image for [prompt], or `null`: no key, network, a provider refusal, an empty or broken answer. */
  suspend fun generate(prompt: String, size: String = IMAGE_SIZE): GeneratedImage? = attempt(prompt, size).image

  /**
   * [generate] that also says whether Meta turned down the [size] itself (HTTP 400 naming `size`), so a
   * caller asking for a non-default frame can fall back to [IMAGE_SIZE] on that alone - not on a network
   * failure or a content refusal, which a second request would only repeat.
   */
  suspend fun attempt(prompt: String, size: String = IMAGE_SIZE): ImageAttempt {
    val key = apiKey()
    if (key.isBlank()) {
      // Without a key the request is guaranteed a 401 — do not spend the network and the step's time on it.
      Log.i(TAG, "No Meta key for image generation — skipping")
      return ImageAttempt(null)
    }
    return try {
      val body = json.encodeToString(
        ImageRequest(
          model = model,
          prompt = prompt,
          size = size,
          responseFormat = RESPONSE_FORMAT,
          outputFormat = OUTPUT_FORMAT,
        ),
      )
      val response = RetryBackoff.send(random, TAG) {
        httpClient.post(endpoint) {
          header("Authorization", "Bearer $key")
          contentType(ContentType.Application.Json)
          setBody(body)
        }
      }
      val text = response.bodyAsText()
      if (!response.status.isSuccess()) {
        // Meta's error body is {"error":{"message","type","param","code"}}, e.g. content_policy_violation.
        Log.w(TAG, "Image generation failed: HTTP ${response.status.value} ${text.take(300)}")
        return ImageAttempt(null, rejectedSize = response.status.value in SIZE_REFUSAL_STATUSES && refusesSize(text))
      }
      val parsed = json.decodeFromString<ImageResponse>(text)
      val drawn = parsed.data.filter { it.b64Json.isNotBlank() }
      val first = drawn.firstOrNull()
      if (first == null) {
        Log.w(TAG, "Image generation returned no image")
        return ImageAttempt(null)
      }
      val bytes = Base64.decode(first.b64Json, Base64.DEFAULT)
      recordUsage(parsed.usage, images = drawn.size)
      // The response has no media type of its own: it says which format it used, and "image/" + that is the type.
      ImageAttempt(GeneratedImage(bytes, "image/" + (parsed.outputFormat?.takeIf { it.isNotBlank() } ?: OUTPUT_FORMAT)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Image generation failed: ${e.message}")
      ImageAttempt(null)
    }
  }

  /**
   * Whether an error body is about the `size` field: Meta's `error.param` names the field when it is sent,
   * otherwise the message has to say "size" as a word. Not seen live for a portrait size - a guess at the
   * documented error shape, kept narrow so a content refusal is never mistaken for it.
   */
  private fun refusesSize(body: String): Boolean {
    val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
    val param = (error?.get("param") as? JsonPrimitive)?.contentOrNull
    if (param != null) return param == "size"
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
    return SIZE_WORD.containsMatchIn(message)
  }

  /**
   * An image is as much a paid model call as a chat turn, and it belongs next to it in the weekly
   * spend card.
   */
  private suspend fun recordUsage(usage: ImageUsage?, images: Int) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = model,
        promptTokens = usage?.inputTokens ?: 0,
        completionTokens = usage?.outputTokens ?: 0,
        // The price is Meta's flat per-image one, not derived from the tokens, so it is known even
        // when the answer carried no usage block.
        costUsd = museImageCostUsd(images),
        estimated = false,
        hadTools = false,
      ),
    )
  }

  @Serializable
  private data class ImageRequest(
    val model: String,
    val prompt: String,
    val size: String,
    val responseFormat: String,
    val outputFormat: String,
  )

  @Serializable
  private data class ImageResponse(
    val data: List<ImageData> = emptyList(),
    val outputFormat: String? = null,
    val usage: ImageUsage? = null,
  )

  @Serializable
  private data class ImageData(val b64Json: String = "")

  @Serializable
  private data class ImageUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
  )

  companion object {
    private const val TAG = "ImageGenClient"
    const val META_IMAGES_URL = "${ModelCatalog.META_BASE_URL}/images/generations"
    const val MUSE_IMAGE_MODEL = "muse-image-1.0"

    /** "WxH" sets only the aspect ratio: 16:9 for the lesson card (the style text asks for the same). */
    const val IMAGE_SIZE = "1280x720"

    /** A phone screen standing up: the forge mockup (ScreenMockupGenerator). */
    const val PORTRAIT_SIZE = "720x1280"
    private const val RESPONSE_FORMAT = "b64_json"

    /** A bad field is a 400 in Meta's docs; 422 is what OpenAI-style APIs use for the same. */
    private val SIZE_REFUSAL_STATUSES = setOf(400, 422)
    private val SIZE_WORD = Regex("""\bsize\b""", RegexOption.IGNORE_CASE)

    /** Meta's default; pinned so the media type we fall back to is the format we asked for. */
    private const val OUTPUT_FORMAT = "webp"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 90_000L
  }
}
