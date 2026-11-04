package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import android.util.Log
import com.aiopenpocket.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest

/**
 * Where the gateway is and what it wants to hear. All of it comes from Settings, read on every call.
 * [metaApiKey] is the person's own Meta key: a forge job's model calls run on it, so it travels with
 * the request (header [META_KEY_HEADER]) and neither the gateway nor the worker keeps a key of their own.
 */
data class ForgeGatewayConfig(val baseUrl: String, val token: String, val metaApiKey: String = "") {
  val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

  override fun toString(): String = "ForgeGatewayConfig(baseUrl=$baseUrl, token=<hidden>, metaApiKey=<hidden>)"

  /** Base without a trailing slash, so relative artifact URLs join cleanly. */
  val normalizedBase: String get() = baseUrl.trim().trimEnd('/')
}

/** Every call ends in one of these; the client throws for cancellation only. */
sealed interface ForgeResult<out T> {
  data class Ok<T>(val value: T) : ForgeResult<T>

  /** [message] is for the person, in their language. */
  data class Failed(val message: String) : ForgeResult<Nothing>
}

/**
 * The app's side of the forge gateway: queue a job, poll it, answer the agent's question, fetch the
 * bundle. Same shape as `WebSearchClient`: the engine is injected, the config is read per request,
 * nothing escapes as an exception.
 *
 * The bundle download verifies the SHA-256 the gateway announced before handing the bytes on: the
 * runtime checks the signature too, but a truncated or swapped download should fail here, with a
 * sentence, rather than inside the interpreter.
 */
class ScreenForgeClient(
  engine: HttpClientEngine,
  private val config: suspend () -> ForgeGatewayConfig,
) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  suspend fun isConfigured(): Boolean = config().isConfigured

  suspend fun submit(request: ForgeCreateJobRequest): ForgeResult<String> = call { cfg ->
    if (cfg.metaApiKey.isBlank()) return@call ForgeResult.Failed(missingMetaKeyMessage())
    val response = httpClient.post("${cfg.normalizedBase}/v1/jobs") {
      authorize(cfg)
      header(META_KEY_HEADER, cfg.metaApiKey)
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(request))
    }
    if (!response.status.isSuccess()) return@call failure(response)
    ForgeResult.Ok(json.decodeFromString<ForgeCreateJobResponse>(response.bodyAsText()).jobId)
  }

  suspend fun status(jobId: String): ForgeResult<ForgeJobView> = call { cfg ->
    val response = httpClient.get("${cfg.normalizedBase}/v1/jobs/$jobId") { authorize(cfg) }
    if (!response.status.isSuccess()) return@call failure(response)
    ForgeResult.Ok(json.decodeFromString<ForgeJobView>(response.bodyAsText()))
  }

  suspend fun answer(jobId: String, text: String): ForgeResult<ForgeJobView> = call { cfg ->
    if (cfg.metaApiKey.isBlank()) return@call ForgeResult.Failed(missingMetaKeyMessage())
    val response = httpClient.post("${cfg.normalizedBase}/v1/jobs/$jobId/answer") {
      authorize(cfg)
      header(META_KEY_HEADER, cfg.metaApiKey)
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ForgeAnswerRequest(text)))
    }
    if (!response.status.isSuccess()) return@call failure(response)
    ForgeResult.Ok(json.decodeFromString<ForgeJobView>(response.bodyAsText()))
  }

  suspend fun downloadBundle(artifact: ForgeArtifact): ForgeResult<ByteArray> = call { cfg ->
    val url = if (artifact.url.startsWith("http://") || artifact.url.startsWith("https://")) {
      artifact.url
    } else {
      cfg.normalizedBase + "/" + artifact.url.trimStart('/')
    }
    val response = httpClient.get(url) {
      authorize(cfg)
      timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
    }
    if (!response.status.isSuccess()) return@call failure(response)
    val bytes: ByteArray = response.body()
    val digest = sha256Hex(bytes)
    if (!digest.equals(artifact.sha256, ignoreCase = true)) {
      Log.w(TAG, "Bundle checksum mismatch for ${artifact.url}: expected ${artifact.sha256}, got $digest")
      return@call ForgeResult.Failed(
        tr(
          "Бандл скачался повреждённым (контрольная сумма не сошлась). Попробуй ещё раз.",
          "The bundle arrived damaged (checksum mismatch). Try again.",
        ),
      )
    }
    ForgeResult.Ok(bytes)
  }

  private suspend fun <T> call(block: suspend (ForgeGatewayConfig) -> ForgeResult<T>): ForgeResult<T> {
    val cfg = config()
    if (!cfg.isConfigured) return ForgeResult.Failed(notConfiguredMessage())
    return try {
      block(cfg)
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpRequestTimeoutException) {
      Log.w(TAG, "Forge gateway timed out: ${e.message}")
      ForgeResult.Failed(tr("Кузница не ответила вовремя.", "The forge did not answer in time."))
    } catch (e: SerializationException) {
      Log.w(TAG, "Forge gateway answered with something unexpected: ${e.message}")
      ForgeResult.Failed(tr("Кузница ответила что-то непонятное.", "The forge answered something unexpected."))
    } catch (e: IOException) {
      Log.w(TAG, "Forge gateway unreachable: ${e.message}")
      ForgeResult.Failed(
        tr(
          "Кузница недоступна: ${e.message ?: "нет сети"}. Проверь адрес в Настройках и сеть.",
          "The forge is unreachable: ${e.message ?: "no network"}. Check the address in Settings and the network.",
        ),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Forge gateway call failed", e)
      ForgeResult.Failed(tr("Кузница: ${e.message ?: "неизвестная ошибка"}", "Forge: ${e.message ?: "unknown error"}"))
    }
  }

  private suspend fun failure(response: HttpResponse): ForgeResult.Failed {
    val detail = runCatching { response.bodyAsText().take(200).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    return ForgeResult.Failed(httpFailureText(response.status.value, detail))
  }

  private fun io.ktor.client.request.HttpRequestBuilder.authorize(cfg: ForgeGatewayConfig) {
    header(HttpHeaders.Authorization, "Bearer ${cfg.token}")
  }

  private fun httpFailureText(status: Int, detail: String?): String {
    val suffix = detail?.let { " ($it)" }.orEmpty()
    return when {
      status == 401 || status == 403 -> tr(
        "Кузница не приняла токен — проверь его в Настройках.",
        "The forge turned the token down - check it in Settings.",
      )
      status == 404 -> tr(
        "Кузница не нашла эту задачу — возможно, её уже убрали.$suffix",
        "The forge no longer has this job - it may have been removed.$suffix",
      )
      status >= 500 -> tr(
        "Кузница сломалась (HTTP $status). Попробуй позже.",
        "The forge failed (HTTP $status). Try later.",
      )
      else -> tr(
        "Кузница отклонила запрос (HTTP $status).$suffix",
        "The forge rejected the request (HTTP $status).$suffix",
      )
    }
  }

  companion object {
    private const val TAG = "ScreenForgeClient"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

    /** Mirrors `ForgeRoutes.META_KEY_HEADER` in the forge contract. */
    const val META_KEY_HEADER = "X-Meta-Api-Key"

    fun missingMetaKeyMessage(): String = tr(
      "Кузница строит экран на твоём ключе Meta. Добавь его в Настройках.",
      "The forge builds the screen on your own Meta key. Add it in Settings.",
    )

    fun notConfiguredMessage(): String = tr(
      "Укажи адрес кузницы и токен в Настройках.",
      "Set the forge address and token in Settings.",
    )

    fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
