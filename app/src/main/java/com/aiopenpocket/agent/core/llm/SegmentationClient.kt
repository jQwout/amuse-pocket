package com.aiopenpocket.agent.core.llm

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/** Why a segmentation did not come back. The lesson turns each one into a different sentence for the user. */
enum class SegmentationFailure {
  /** No Meta Model API key: SAM needs the person's own key. */
  NO_KEY,
  /** The concept is empty or is not one noun phrase (a list, a command). Never sent: it would cost a call. */
  BAD_CONCEPT,
  /** The picture would not fit the schema's limit, or the server said 413. */
  IMAGE_TOO_LARGE,
  /** 400: the prompt or the metadata was refused. Not retried — the same request fails the same way. */
  BAD_REQUEST,
  /** 401 / 403. */
  UNAUTHORIZED,
  /** 402 `billing_not_configured`: the key is fine, the account has no billing. */
  BILLING,
  /** 404 `model_not_found`: the team has no access to `sam-3.1`. */
  NO_MODEL_ACCESS,
  /** 429 after the retries were spent. */
  RATE_LIMITED,
  /** 5xx / 504 after the retries were spent. */
  SERVER,
  /** The transport failed: no network, a timeout, a cut connection. */
  NETWORK,
  /** The answer arrived but is not SAM's grammar. Reported rather than shown as "found nothing". */
  BAD_RESPONSE,
  /** The stream ended with no `response.completed`: whether anything was found is unknown. */
  INCOMPLETE,
  /** The model refused or the response failed. */
  REFUSED,
}

sealed interface SegmentationResult {
  /**
   * The model answered. [records] may be EMPTY: zero matches is a normal answer (an empty `output_text`),
   * not an error. [diagnostics] are the parser's warnings and any malformed lines it dropped.
   */
  data class Success(
    val concept: String,
    val records: List<SegmentRecord>,
    val diagnostics: List<SegmentationDiagnostic> = emptyList(),
  ) : SegmentationResult

  data class Failed(val reason: SegmentationFailure, val detail: String? = null) : SegmentationResult
}

/**
 * SAM 3.1 outlining things in a photo — the direct Meta Model API (`POST https://api.meta.ai/v1/responses`,
 * model `sam-3.1`).
 *
 * A separate client in the mould of [VideoUnderstandingClient] rather than a branch of
 * [OpenAiCompatClient]: this is the Responses API, not chat completions, the answer is not a message but
 * a text grammar (see [SegmentationParser]), there are no tools and no model chain, and the lesson wants
 * one question and one answer about one photo.
 *
 * The call streams (`stream: true`) and the client assembles the stream itself, to the end, before it
 * returns — the user sees "looking…" on the card, not partial text. Only `response.output_text.delta`
 * events are read for the text, and `response.completed` closes the call. Meta's cookbook warns that
 * the API sends `response.content_part.done` but NOT `response.output_text.done`, so nothing here waits
 * for the latter; `content_part.done` is used only as a fallback when no delta came.
 *
 * Does NOT throw (except on cancellation): every failure is a [SegmentationResult.Failed] and a line in
 * the log. The log never carries the key or the picture.
 *
 * **Spend.** SAM is billed per picture, not per token: $0.0025 each. A call is recorded as
 * `usage.images_processed` × that price; when no usage came, one call counts as one picture and the row
 * is marked estimated. NOT checked against a live response — nobody has made a paid call (zero budget),
 * so the exact place of `images_processed` in `response.completed` follows Meta's schema as it was read.
 *
 * What is recorded follows ONE rule: did Meta accept the picture? Once an HTTP 2xx came, it did, and the call
 * is recorded whatever happens next — a zero-match answer, an answer we cannot read, a stream that ends
 * without `response.completed`, a connection that breaks while reading, even a cancel by the learner. The
 * last three are recorded as estimates, because the picture count never arrived. Nothing is recorded when
 * Meta rejected the request (a non-2xx status, retried or not) or when no response came at all: nothing was
 * processed. A stream that carries an `error`/`failed`/refusal event is not recorded either — Meta said the
 * work did not complete — which is a judgment about the schema, not something seen in a live answer.
 */
class SegmentationClient(
  engine: HttpClientEngine,
  /** The key is read on every request; an empty one means "do not send" (see LlmModule). */
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = META_RESPONSES_URL,
  val model: String = SAM_MODEL,
  private val random: Random = Random.Default,
) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      // The photo goes up as base64 on a phone connection, then SAM thinks: longer than a chat turn, but
      // a hung request must not hold a lesson step forever.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
      socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
  }

  /** Whether a key is set — lets the lesson say "add a Meta key" before it prepares a photo for nothing. */
  fun isConfigured(): Boolean = apiKey().isNotBlank()

  /** Outlines every match of [concept] in [image]. [concept] is one English noun phrase, see [SegmentationConcept]. */
  suspend fun segment(concept: String, image: ByteArray, mimeType: String): SegmentationResult {
    val key = apiKey()
    if (key.isBlank()) {
      Log.i(TAG, "No Meta key — skipping segmentation")
      return SegmentationResult.Failed(SegmentationFailure.NO_KEY)
    }
    val phrase = SegmentationConcept.normalize(concept)
      ?: return SegmentationResult.Failed(SegmentationFailure.BAD_CONCEPT, SegmentationConcept.problemOf(concept)?.name)
    return try {
      val dataUrl = "data:$mimeType;base64," + Base64.encodeToString(image, Base64.NO_WRAP)
      if (dataUrl.length > MAX_IMAGE_URL_CHARS) {
        Log.w(TAG, "Picture is over the schema limit: ${dataUrl.length} chars")
        return SegmentationResult.Failed(SegmentationFailure.IMAGE_TOO_LARGE)
      }
      val body = requestBody(phrase, dataUrl)
      var attempt = 1
      var outcome = send(key, body, phrase)
      while (outcome is Attempt.Retryable && attempt < MAX_ATTEMPTS) {
        val wait = retryDelayMs(attempt, outcome.retryAfter, random)
        if (wait > MAX_WAIT_MS) {
          // "Come back in a minute" is no use to someone standing on a lesson step.
          Log.w(TAG, "Retry-After asks for ${wait}ms — giving up")
          break
        }
        Log.i(TAG, "HTTP ${outcome.status}, attempt $attempt of $MAX_ATTEMPTS, waiting ${wait}ms")
        delay(wait)
        attempt++
        outcome = send(key, body, phrase)
      }
      when (outcome) {
        is Attempt.Done -> outcome.result
        is Attempt.Retryable -> outcome.failure
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Segmentation failed: ${e.message}")
      SegmentationResult.Failed(SegmentationFailure.NETWORK, e.message)
    }
  }

  private sealed interface Attempt {
    data class Done(val result: SegmentationResult) : Attempt

    /** 429 or 5xx: worth another try; [failure] is what to report if the tries run out. */
    data class Retryable(val status: Int, val retryAfter: String?, val failure: SegmentationResult.Failed) : Attempt
  }

  private suspend fun send(key: String, body: String, phrase: String): Attempt =
    httpClient.preparePost(endpoint) {
      header(HttpHeaders.Authorization, "Bearer $key")
      header(HttpHeaders.Accept, "text/event-stream")
      contentType(ContentType.Application.Json)
      setBody(body)
    }.execute { response ->
      val status = response.status.value
      if (status !in 200..299) {
        val text = response.bodyAsText()
        Log.w(TAG, "Segmentation failed: HTTP $status ${text.replace("\n", " ").take(300)}")
        return@execute failureFor(status, response.headers[HttpHeaders.RetryAfter])
      }
      // From here on the picture was accepted: a read that breaks, or is cancelled, is still a billed call.
      val stream = try {
        consumeResponsesSse(response.body<ByteReadChannel>(), json)
      } catch (e: CancellationException) {
        withContext(NonCancellable) { recordUsage(null) }
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Segmentation stream broke after HTTP $status: ${e.message}")
        recordUsage(null)
        return@execute Attempt.Done(SegmentationResult.Failed(SegmentationFailure.NETWORK, e.message))
      }
      Attempt.Done(finish(phrase, stream))
    }

  private fun failureFor(status: Int, retryAfter: String?): Attempt = when {
    status == 429 -> Attempt.Retryable(status, retryAfter, SegmentationResult.Failed(SegmentationFailure.RATE_LIMITED, "HTTP 429"))
    status >= 500 -> Attempt.Retryable(status, retryAfter, SegmentationResult.Failed(SegmentationFailure.SERVER, "HTTP $status"))
    else -> Attempt.Done(
      SegmentationResult.Failed(
        when (status) {
          401, 403 -> SegmentationFailure.UNAUTHORIZED
          402 -> SegmentationFailure.BILLING
          404 -> SegmentationFailure.NO_MODEL_ACCESS
          413 -> SegmentationFailure.IMAGE_TOO_LARGE
          // 400 and every other 4xx: the request itself is wrong, and sending it again will not change that.
          else -> SegmentationFailure.BAD_REQUEST
        },
        "HTTP $status",
      ),
    )
  }

  private suspend fun finish(phrase: String, stream: ResponsesStream): SegmentationResult {
    stream.failure?.let {
      Log.w(TAG, "Segmentation failed in the stream: $it")
      return SegmentationResult.Failed(SegmentationFailure.REFUSED, it)
    }
    if (!stream.completed) {
      // Without response.completed, "no records" would be a guess dressed up as "found nothing". The HTTP 2xx
      // already came, though: the picture was accepted, so the call is recorded (as an estimate).
      Log.w(TAG, "Segmentation stream ended without response.completed")
      recordUsage(stream.imagesProcessed)
      return SegmentationResult.Failed(SegmentationFailure.INCOMPLETE)
    }
    val text = stream.text
    val parsed = SegmentationParser.parse(text)
    parsed.diagnostics.forEach { Log.w(TAG, "${it.severity} ${it.code} line ${it.line}: ${it.message}") }
    // An empty answer is the documented "zero matches". Text with no record in it at all is not.
    // The call was processed and billed whether or not anything was found, and whether or not we can read it.
    recordUsage(stream.imagesProcessed)
    if (parsed.records.isEmpty() && text.isNotBlank()) {
      // The length and the fact, not the text: the model's prose can describe a private photo, and logcat is not
      // the place for it.
      Log.w(TAG, "No SAM records in the answer (${text.length} chars of text)")
      return SegmentationResult.Failed(SegmentationFailure.BAD_RESPONSE, "no records in ${text.length} chars of text")
    }
    return SegmentationResult.Success(phrase, parsed.records, parsed.diagnostics)
  }

  private suspend fun recordUsage(imagesProcessed: Int?) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = model,
        // Billed per picture: there are no tokens to report, and zeros here are not a measurement.
        promptTokens = 0,
        completionTokens = 0,
        costUsd = samCostUsd(imagesProcessed),
        // The provider named no picture count — one call is counted as one picture, and the row says so.
        estimated = imagesProcessed == null,
        hadTools = false,
      ),
    )
  }

  private fun requestBody(concept: String, dataUrl: String): String = buildJsonObject {
    put("model", model)
    putJsonArray("input") {
      addJsonObject {
        put("type", "message")
        put("role", "user")
        putJsonArray("content") {
          addJsonObject {
            put("type", "input_text")
            put("text", concept)
          }
          addJsonObject {
            put("type", "input_image")
            put("image_url", dataUrl)
          }
        }
      }
    }
    put("stream", true)
    // The learner's photo must not stay on Meta's side after the call: store:false asks the API not to keep the
    // request. How Meta honors it is not verified (no live call); the lesson's privacy text must not promise more.
    put("store", false)
    putJsonObject("metadata") {
      // Stage 1 draws boxes only; one_bit is the smaller mask encoding and the one stage 2 will decode.
      put("mask_encoding", "one_bit")
      put("include_confidence", "true")
    }
  }.toString()

  companion object {
    private const val TAG = "SegmentationClient"
    const val META_RESPONSES_URL = "${ModelCatalog.META_BASE_URL}/responses"

    /** With a DOT. The marketing page writes `sam-3-1`; that is Meta's typo, the API does not know it. */
    const val SAM_MODEL = "sam-3.1"

    /** The `image_url` limit by Meta's schema, in characters of the whole data URL. */
    const val MAX_IMAGE_URL_CHARS = 20_971_520

    const val MAX_ATTEMPTS = 3
    private const val MAX_WAIT_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 120_000L
    private const val SOCKET_TIMEOUT_MS = 60_000L

    /** The shared Meta backoff — see [RetryBackoff]. */
    internal fun retryDelayMs(attempt: Int, retryAfter: String?, random: Random): Long =
      RetryBackoff.delayMs(attempt, retryAfter, random)
  }
}

/**
 * What the lesson may send as the concept. SAM takes ONE noun phrase per request, in English
 * («cup», «yellow school bus»): a comma-separated list or a command («segment the cup») does not work.
 * Rejecting those here costs nothing; sending them costs a call.
 */
object SegmentationConcept {
  const val MAX_LENGTH = 64

  /**
   * [NOT_ENGLISH]: anything but Latin letters, digits, space, hyphen and apostrophe. SAM is asked in English, and a
   * Russian «чашка» sails through every other check, costs a call and comes back as "nothing found".
   */
  enum class Problem { EMPTY, LIST, RESERVED_CHARACTERS, TOO_LONG, NOT_ENGLISH }

  fun problemOf(raw: String): Problem? {
    val text = raw.trim()
    return when {
      text.isEmpty() -> Problem.EMPTY
      // The object-prompt grammar of the same API is made of these; a noun phrase must not smuggle it in.
      text.any { it == '<' || it == '>' || it == '|' } -> Problem.RESERVED_CHARACTERS
      text.any { it == ',' || it == ';' || it == '\n' || it == '\r' } -> Problem.LIST
      text.length > MAX_LENGTH -> Problem.TOO_LONG
      !text.all { isEnglishConceptChar(it) } -> Problem.NOT_ENGLISH
      else -> null
    }
  }

  // A typographic apostrophe is what a phone keyboard types in "person's"; it is sent as the plain one.
  private fun isEnglishConceptChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' ' || c == '\t' || c == '-' || c == '\'' || c == '\u2019'

  /** The phrase as it will be sent (whitespace collapsed), or `null` when [problemOf] objects to it. */
  fun normalize(raw: String): String? =
    if (problemOf(raw) == null) raw.trim().replace(Regex("\\s+"), " ").replace('\u2019', '\'') else null
}

/** The result of reading one Responses stream to its end. */
internal class ResponsesStream(
  val text: String,
  val completed: Boolean,
  val imagesProcessed: Int?,
  /** An error / failed / refusal event, with its message. */
  val failure: String?,
)

/**
 * Reads a Responses-API SSE stream. Only the JSON in each `data:` line matters — events are told apart
 * by its `type`, not by the `event:` line.
 *
 * The text is the concatenation of `response.output_text.delta` deltas. `response.completed` ends the
 * call and carries `usage`. A `data:` chunk that does not parse is skipped, never fatal, as in
 * [consumeOpenAiSse]: a stray keep-alive must not abort a paid call.
 */
internal suspend fun consumeResponsesSse(channel: ByteReadChannel, json: Json): ResponsesStream {
  val reader = ResponsesStreamReader()
  while (!channel.isClosedForRead) {
    val line = channel.readUTF8Line() ?: continue
    if (!line.startsWith("data:")) continue
    val data = line.removePrefix("data:").trim()
    if (data.isEmpty() || data == "[DONE]") continue
    val event = try {
      json.parseToJsonElement(data).jsonObject
    } catch (e: Exception) {
      Log.w("SegmentationClient", "skipping unparseable SSE chunk: ${e.message}")
      continue
    }
    reader.accept(event)
    // response.completed is the last word: whatever the line does after it (a reset, a trailing [DONE]) cannot
    // change the answer, and must not be able to turn a finished call into a failed one.
    if (reader.isCompleted) break
  }
  val result = reader.result()
  // A channel closed WITH a cause reads as "closed for read" and ends the loop quietly, so a dropped connection
  // would look like a short answer. If the call never completed, that is a transport failure, not an incomplete one.
  channel.closedCause?.let { if (!result.completed) throw it }
  return result
}

internal class ResponsesStreamReader {
  private val deltas = StringBuilder()
  private var sawDelta = false
  private var partText: String? = null
  private var completed = false
  private var imagesProcessed: Int? = null
  private var failure: String? = null

  val isCompleted: Boolean get() = completed

  fun accept(event: JsonObject) {
    when (val type = event.string("type")) {
      "response.output_text.delta" -> {
        val delta = event.string("delta")
        if (delta != null) {
          deltas.append(delta)
          sawDelta = true
        }
      }
      // The full text of the part. The API sends this one but not output_text.done, so it is only a
      // fallback for a stream that carried no delta, never something to wait for.
      "response.content_part.done" -> {
        val part = event["part"] as? JsonObject
        if (part?.string("type") == "output_text") partText = part.string("text")
      }
      "response.completed" -> {
        completed = true
        val usage = (event["response"] as? JsonObject)?.get("usage") as? JsonObject
        imagesProcessed = (usage?.get("images_processed") as? JsonPrimitive)?.intOrNull
      }
      "error" -> failure = errorMessage(event) ?: "error event"
      "response.failed" -> failure = errorMessage(event["response"] as? JsonObject) ?: "response failed"
      "response.incomplete" -> failure = "response incomplete"
      else -> if (type != null && type.startsWith("response.refusal")) failure = "refusal"
    }
  }

  fun result(): ResponsesStream {
    val text = if (sawDelta) deltas.toString() else partText.orEmpty()
    return ResponsesStream(text, completed, imagesProcessed, failure)
  }

  private fun errorMessage(holder: JsonObject?): String? {
    val error = holder?.get("error")
    return (error as? JsonObject)?.string("message") ?: (error as? JsonPrimitive)?.contentOrNull ?: holder?.string("message")
  }

  /** A string field, or null when it is missing or is not a primitive — a strange event must not crash a paid call. */
  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
