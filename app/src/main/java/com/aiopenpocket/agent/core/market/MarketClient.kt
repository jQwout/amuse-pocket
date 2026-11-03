package com.aiopenpocket.agent.core.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import android.util.Log
import com.aiopenpocket.agent.core.forge.ForgeGatewayConfig
import com.aiopenpocket.agent.core.forge.ForgeResult
import com.aiopenpocket.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * The app's side of the market on the forge gateway: find skills, open one, count an install. Same
 * shape as `ScreenForgeClient` (engine injected, config read per call, nothing escapes as an
 * exception), with one difference that is the point: these routes are public, so no token is sent
 * and no token is required. The forge token lives only in the Settings of the few who typed it; a
 * market behind it would be empty for nearly everyone, judges included.
 *
 * With no gateway address in Settings (and no debug default) the calls go to [PUBLIC_BASE_URL].
 */
class MarketClient(
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

  /** The gateway's address without a trailing slash: Settings, or the public one when Settings is empty. */
  suspend fun baseUrl(): String = config().normalizedBase.ifBlank { PUBLIC_BASE_URL }

  /** A gateway-relative path (a cover's `coverUrl`) as an absolute URL an image loader can fetch. */
  suspend fun absoluteUrl(path: String): String =
    if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl() + "/" + path.trimStart('/')

  /** Listed skills, newest first; [query] matches title, summary and tags on the server. */
  suspend fun searchSkills(query: String?, page: Int = 1, limit: Int = DEFAULT_PAGE_SIZE): ForgeResult<MarketListingPage> = call { base ->
    val response = httpClient.get("$base/v1/market/listings") {
      parameter("kind", "skill")
      query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("q", it) }
      parameter("page", page)
      parameter("limit", limit)
    }
    // A 404 on the catalog itself means a gateway without the market routes (the build on Fly before the
    // market shipped; the owner chose, 3 Nov 2026, to keep the button and not deploy for now): the honest
    // sentence is "not available yet", not "this skill is gone".
    if (!response.status.isSuccess()) return@call failure(response, notFound = MARKET_NOT_AVAILABLE)
    ForgeResult.Ok(json.decodeFromString<MarketListingPage>(response.bodyAsText()))
  }

  /** The listing with its latest version and the AI review of that version. */
  suspend fun listing(listingId: String): ForgeResult<MarketListingDetail> = call { base ->
    val response = httpClient.get("$base/v1/market/listings/$listingId")
    if (!response.status.isSuccess()) return@call failure(response)
    ForgeResult.Ok(json.decodeFromString<MarketListingDetail>(response.bodyAsText()))
  }

  /** Counts this install once per [installId]; the server answers 204. */
  suspend fun install(listingId: String, installId: String): ForgeResult<Unit> = call { base ->
    val response = httpClient.post("$base/v1/market/listings/$listingId/install") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(MarketInstallRequest(installId)))
    }
    if (!response.status.isSuccess()) return@call failure(response)
    ForgeResult.Ok(Unit)
  }

  private suspend fun <T> call(block: suspend (base: String) -> ForgeResult<T>): ForgeResult<T> {
    val base = baseUrl()
    return try {
      block(base)
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpRequestTimeoutException) {
      Log.w(TAG, "Market timed out: ${e.message}")
      ForgeResult.Failed(tr("Маркет не ответил вовремя.", "The market did not answer in time."))
    } catch (e: SerializationException) {
      Log.w(TAG, "Market answered with something unexpected: ${e.message}")
      ForgeResult.Failed(tr("Маркет ответил что-то непонятное.", "The market answered something unexpected."))
    } catch (e: IOException) {
      Log.w(TAG, "Market unreachable: ${e.message}")
      ForgeResult.Failed(
        tr(
          "Маркет недоступен: ${e.message ?: "нет сети"}. Проверь сеть и адрес кузницы в Настройках.",
          "The market is unreachable: ${e.message ?: "no network"}. Check the network and the forge address in Settings.",
        ),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Market call failed", e)
      ForgeResult.Failed(tr("Маркет: ${e.message ?: "неизвестная ошибка"}", "Market: ${e.message ?: "unknown error"}"))
    }
  }

  private suspend fun failure(response: HttpResponse, notFound: () -> String = SKILL_GONE): ForgeResult.Failed {
    val detail = runCatching { response.bodyAsText().take(200).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    return ForgeResult.Failed(httpFailureText(response.status.value, detail, notFound))
  }

  private fun httpFailureText(status: Int, detail: String?, notFound: () -> String): String {
    val suffix = detail?.let { " ($it)" }.orEmpty()
    return when {
      status == 404 -> notFound()
      status == 429 -> tr(
        "Слишком много запросов к маркету, попробуй чуть позже.",
        "Too many requests to the market, try again in a bit.",
      )
      status == 503 -> tr(
        "Маркет на этом сервере выключен.$suffix",
        "The market is off on this server.$suffix",
      )
      status >= 500 -> tr(
        "Маркет сломался (HTTP $status). Попробуй позже.",
        "The market failed (HTTP $status). Try later.",
      )
      else -> tr(
        "Маркет отклонил запрос (HTTP $status).$suffix",
        "The market rejected the request (HTTP $status).$suffix",
      )
    }
  }

  companion object {
    private const val TAG = "MarketClient"

    /** 404 on one listing: it was there and is not any more. */
    private val SKILL_GONE: () -> String = {
      tr("Этого навыка в маркете больше нет.", "This skill is no longer on the market.")
    }

    /** 404 on the catalog: this gateway has no market at all (yet). */
    private val MARKET_NOT_AVAILABLE: () -> String = {
      tr("Маркет пока недоступен. Загляни позже.", "The market is not available yet. Check back later.")
    }
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 30_000L
    const val DEFAULT_PAGE_SIZE = 20

    /** The deployed gateway (`forge/gateway/fly.toml`); the public routes need no token, so this is a usable default. */
    const val PUBLIC_BASE_URL = "https://amuse-forge.fly.dev"
  }
}
