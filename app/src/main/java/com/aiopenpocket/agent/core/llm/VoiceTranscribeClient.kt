package com.aiopenpocket.agent.core.llm

// @trace meta-voice-transcribe-in-calls-297760

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

sealed interface TranscribeResult {
  /** [text] may be blank: Meta heard nothing it could write down. */
  data class Heard(val text: String) : TranscribeResult

  data class Failed(val reason: Reason, val status: Int? = null) : TranscribeResult

  enum class Reason { NO_KEY, UNAUTHORIZED, BAD_AUDIO, RATE_LIMITED, SERVER, NETWORK, BAD_RESPONSE }
}

/**
 * Meta's speech-to-text, Muse Voice Transcribe, on the direct Meta Model API: one recorded phrase in, its text
 * out (`POST https://api.meta.ai/v1/asr/transcribe`, dev.meta.ai/docs/api-reference/voice/transcribe, read
 * 4 Nov 2026).
 *
 * Its own endpoint, not chat completions or responses: a multipart body with a `request` JSON part (model,
 * `audioEncoding: WAV`, `mode: PUSH_TO_TALK` — the whole clip is one turn) and an `audio` part, a mono 16-bit
 * WAV at 16 kHz (what [com.aiopenpocket.voice.call.PhraseRecorder] makes). The answer's text is `transcript`.
 * The language is a hint (`languageBias`, language NAMES), not a requirement.
 *
 * English yes, Russian no: Russian is not among the model's 25 languages, which is why the call uses it only
 * when the app speaks English.
 *
 * **Spend.** $0.18 per hour of audio, metered in whole seconds rounded down; failed and 429 requests are not
 * billed. A call is recorded from the answer's `audioDurationMs`, or, when the answer lacks it, from the
 * length we sent, marked estimated. Not seen on a live bill.
 *
 * Does not throw (except on cancellation). The log carries neither the key, the audio nor the text.
 */
class VoiceTranscribeClient(
  engine: HttpClientEngine,
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val endpoint: String = TRANSCRIBE_URL,
  val model: String = MODEL,
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = 10_000L
      // A phrase is seconds of audio; a call waits in silence for this, so a hung request must end soon.
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
      socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  fun isConfigured(): Boolean = apiKey().isNotBlank()

  /** [wav] is the whole phrase; [durationMs] its length, for the spend estimate; [language] a name like "English". */
  suspend fun transcribe(wav: ByteArray, durationMs: Long, language: String?): TranscribeResult {
    val key = apiKey()
    if (key.isBlank()) return TranscribeResult.Failed(TranscribeResult.Reason.NO_KEY)
    val request = buildJsonObject {
      put("model", model)
      put("audioEncoding", "WAV")
      put("mode", "PUSH_TO_TALK")
      if (language != null) putJsonArray("languageBias") { add(language) }
    }.toString()
    return try {
      val response = httpClient.submitFormWithBinaryData(
        url = endpoint,
        formData = formData {
          append("request", request, Headers.build { append(HttpHeaders.ContentType, "application/json") })
          append(
            "audio",
            wav,
            Headers.build {
              append(HttpHeaders.ContentType, "audio/wav")
              append(HttpHeaders.ContentDisposition, "filename=\"phrase.wav\"")
            },
          )
        },
      ) {
        header(HttpHeaders.Authorization, "Bearer $key")
        header(HttpHeaders.Accept, "application/json")
      }
      val status = response.status.value
      if (!response.status.isSuccess()) {
        android.util.Log.w(TAG, "transcribe failed: HTTP $status")
        return TranscribeResult.Failed(reasonFor(status), status)
      }
      val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        ?: return TranscribeResult.Failed(TranscribeResult.Reason.BAD_RESPONSE, status)
      val text = body.string("transcript") ?: return TranscribeResult.Failed(TranscribeResult.Reason.BAD_RESPONSE, status)
      val billedMs = ((body["audioDurationMs"] as? JsonPrimitive)?.intOrNull)?.toLong()
      recordUsage(billedMs ?: durationMs, estimated = billedMs == null)
      android.util.Log.d(TAG, "transcribed ${billedMs ?: durationMs} ms of audio into ${text.length} chars")
      TranscribeResult.Heard(text.trim())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      android.util.Log.w(TAG, "transcribe failed: ${e.javaClass.simpleName}")
      TranscribeResult.Failed(TranscribeResult.Reason.NETWORK)
    }
  }

  private fun reasonFor(status: Int): TranscribeResult.Reason = when (status) {
    401, 403 -> TranscribeResult.Reason.UNAUTHORIZED
    400, 406, 413 -> TranscribeResult.Reason.BAD_AUDIO
    429 -> TranscribeResult.Reason.RATE_LIMITED
    else -> TranscribeResult.Reason.SERVER
  }

  private suspend fun recordUsage(audioMs: Long, estimated: Boolean) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = model,
        // Billed per second of audio: there are no tokens to report.
        promptTokens = 0,
        completionTokens = 0,
        costUsd = (audioMs / 1_000) * PRICE_PER_SECOND_USD,
        estimated = estimated,
        hadTools = false,
      ),
    )
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  companion object {
    private const val TAG = "VoiceTranscribe"
    const val TRANSCRIBE_URL = "${ModelCatalog.META_BASE_URL}/asr/transcribe"
    const val MODEL = "muse-voice-transcribe-1.0"
    const val REQUEST_TIMEOUT_MS = 20_000L

    /** $0.18 per hour of audio (dev.meta.ai/docs/pricing-rate-limits, 4 Nov 2026). */
    internal const val PRICE_PER_SECOND_USD = 0.18 / 3600
  }
}
