package com.aiopenpocket.agent.core.llm

// @trace web-search-tool-c73ed8

import android.util.Log
import com.aiopenpocket.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** How much the web search may read: Meta's `search_context_size`, and a ceiling on searches per call (money). */
enum class SearchDepth(val contextSize: String, val maxSearches: Int) {
  QUICK("low", 1),
  NORMAL("medium", 2),
  DEEP("high", 4),
  ;

  companion object {
    /** The model's `depth` argument; anything unknown or missing is [NORMAL] rather than an error. */
    fun parse(raw: String?): SearchDepth = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NORMAL
  }
}

/** One cited source: the page title (the host when Meta sent none) and its address. */
data class WebSource(val title: String, val url: String)

/** What a search produced: the model's answer text, the pages it cited (deduplicated) and how many searches ran. */
data class WebSearchAnswer(val text: String, val sources: List<WebSource>, val searches: Int)

sealed interface WebSearchResult {
  data class Found(val answer: WebSearchAnswer) : WebSearchResult

  /** [message] is for the person and the model alike, already in the interface language. */
  data class Failed(val message: String) : WebSearchResult
}

/**
 * Meta's server-side web search, on the direct Meta Model API, on the person's own key.
 *
 * It is a separate client because the app talks to Meta through Chat Completions, where the only tool
 * type is `function` (`web_search_options` is a 400), while the hosted `web_search` exists ONLY in the
 * Responses API (`POST /v1/responses`, dev.meta.ai/docs/search-grounding, "Constraints"). So the main
 * loop stays as it is and the search is a bridge: one self-contained request per call, with its own
 * model run, that comes back as an answer plus the pages it cited.
 *
 * The request carries `model` (the catalog's current Spark, see [preferredSparkId]), `input` (the query),
 * `instructions`, `tools: [{type: web_search, search_context_size}]`, `max_tool_calls`, `store: false`
 * (nothing is kept on Meta's side) and `reasoning: {effort: low}` (a lookup does not need long thinking,
 * and reasoning tokens are paid for). All of them are documented in the Responses `Create Response`
 * schema; Meta answers 400 to unknown top-level fields, so nothing else is added.
 *
 * No retries on 429/503, unlike [RetryBackoff] callers: a search is called from the middle of a chat
 * turn, a wait of tens of seconds would stall the whole turn, and the model gets the error and can
 * decide for itself.
 *
 * Does NOT throw (except on cancellation): every failure is a [WebSearchResult.Failed] with a text the
 * model can act on.
 *
 * **Spend.** Tokens from the response `usage` priced with [metaListPriceUsd], plus [webSearchCostUsd]
 * for every `web_search_call` item. Recorded whenever Meta accepted the request and answered with JSON,
 * because the money is spent then even if no answer text came back. Not seen on a live bill (no paid
 * call has been made): the row is marked `estimated` when `usage` is absent or the model is not on the
 * price list.
 */
class WebSearchClient(
  engine: HttpClientEngine,
  /** The key is read on every request; an empty one means "do not send" (see LlmModule). */
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  /** The model id, read on every request so a catalog change applies without a restart. */
  private val model: () -> String = { FALLBACK_SPARK_MODEL },
  private val endpoint: String = SegmentationClient.META_RESPONSES_URL,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // `store: false` is the default of nothing - it must be sent, so defaults are encoded.
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      // Search plus a model run: longer than a plain call. Below the tool ceiling (WebSearchTool.timeoutMs),
      // so a slow answer ends here with a clear text rather than as the executor's generic "timed out".
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  /** The request body exactly as it is sent - split out so the tests can pin the shape. */
  internal fun requestBody(query: String, depth: SearchDepth, modelId: String): String = json.encodeToString(
    ResponsesRequest(
      model = modelId,
      input = query,
      instructions = INSTRUCTIONS,
      tools = listOf(WebSearchToolParam(searchContextSize = depth.contextSize)),
      maxToolCalls = depth.maxSearches,
    ),
  )

  suspend fun search(query: String, depth: SearchDepth = SearchDepth.NORMAL): WebSearchResult {
    val key = apiKey()
    if (key.isBlank()) {
      // Guaranteed 401 otherwise; the same text every other model action gives for a missing key.
      Log.i(TAG, "No Meta key for web search - skipping")
      return WebSearchResult.Failed(missingKeyMessage())
    }
    val modelId = model()
    return try {
      val response = httpClient.post(endpoint) {
        header("Authorization", "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(requestBody(query, depth, modelId))
      }
      val text = response.bodyAsText()
      if (!response.status.isSuccess()) {
        val detail = WebSearchResponseParser.errorMessage(text)
        Log.w(TAG, "Web search failed: HTTP ${response.status.value} ${text.take(300)}")
        return WebSearchResult.Failed(httpFailureText(response.status.value, detail))
      }
      val parsed = WebSearchResponseParser.parse(text)
        ?: return WebSearchResult.Failed(
          tr(
            "Поиск в интернете вернул ответ, который не удалось разобрать.",
            "Web search returned an answer that could not be read.",
          ),
        )
      // The search ran and was billed whatever the text turns out to be.
      recordUsage(modelId, parsed)
      parsed.failure?.let {
        return WebSearchResult.Failed(tr("Поиск в интернете не удался: $it", "Web search failed: $it"))
      }
      if (parsed.text.isBlank()) {
        return WebSearchResult.Failed(
          tr(
            "Поиск в интернете не вернул текста ответа. Переформулируй запрос или ответь без поиска.",
            "Web search returned no answer text. Rephrase the query or answer without searching.",
          ),
        )
      }
      WebSearchResult.Found(WebSearchAnswer(parsed.text, parsed.sources, parsed.searches))
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpRequestTimeoutException) {
      Log.w(TAG, "Web search timed out: ${e.message}")
      WebSearchResult.Failed(
        tr(
          "Поиск в интернете не уложился во время (${REQUEST_TIMEOUT_MS / 1000} с). Попробуй запрос короче или ответь без поиска.",
          "Web search timed out after ${REQUEST_TIMEOUT_MS / 1000}s. Try a shorter query or answer without searching.",
        ),
      )
    } catch (e: Exception) {
      Log.w(TAG, "Web search failed: ${e.message}")
      WebSearchResult.Failed(
        tr(
          "Поиск в интернете не удался: нет сети или сбой у провайдера.",
          "Web search failed: there is no network, or the provider is down.",
        ),
      )
    }
  }

  private suspend fun recordUsage(modelId: String, parsed: ParsedResponse) {
    val usage = parsed.usage
    val tokenPrice = usage?.let { metaListPriceUsd(modelId, it) }
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = modelId,
        promptTokens = usage?.promptTokens ?: 0,
        completionTokens = usage?.completionTokens ?: 0,
        // Tokens by the list price plus a flat fee per search; with no token price the search fee still counts.
        costUsd = (tokenPrice ?: 0.0) + webSearchCostUsd(parsed.searches),
        // No usage block, or a model off the price list: the token part is a guess (zero), not a measurement.
        estimated = tokenPrice == null,
        hadTools = true,
      ),
    )
  }

  /** A reason for the model and the person: what happened and whether trying again makes sense. */
  private fun httpFailureText(status: Int, detail: String?): String {
    val suffix = detail?.let { " ($it)" }.orEmpty()
    return when {
      status == 401 || status == 403 -> tr(
        "Поиск в интернете: ключ Meta Model API не подошёл — проверь его в Настройках.$suffix",
        "Web search: Meta Model API turned the key down - check it in Settings.$suffix",
      )
      status == 402 -> tr(
        "Поиск в интернете: на счёте Meta Model API закончились средства.",
        "Web search: the Meta Model API account has run out of credit.",
      )
      status == 429 -> tr(
        "Поиск в интернете: слишком много запросов (HTTP 429). Не повторяй сразу — ответь без поиска или попробуй позже.",
        "Web search: too many requests (HTTP 429). Do not retry at once - answer without searching or try later.",
      )
      status >= 500 -> tr(
        "Поиск в интернете: сбой у провайдера (HTTP $status). Попробуй позже или ответь без поиска.",
        "Web search: the provider failed (HTTP $status). Try later or answer without searching.",
      )
      else -> tr(
        "Поиск в интернете: запрос отклонён (HTTP $status).$suffix",
        "Web search: the request was rejected (HTTP $status).$suffix",
      )
    }
  }

  @Serializable
  private data class ResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String,
    val tools: List<WebSearchToolParam>,
    val maxToolCalls: Int,
    val store: Boolean = false,
    val reasoning: ReasoningParam = ReasoningParam(),
  )

  @Serializable
  private data class WebSearchToolParam(val searchContextSize: String, val type: String = "web_search")

  @Serializable
  private data class ReasoningParam(val effort: String = "low")

  companion object {
    private const val TAG = "WebSearchClient"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 55_000L

    /** Keeps the answer short: it goes back into the agent's context, and output tokens are paid for. */
    private const val INSTRUCTIONS =
      "Answer the question concisely and factually from current web sources. Prefer specifics (names, dates, " +
        "numbers) over background. Keep the answer under about 300 words, with no greeting or closing remarks."
  }
}

/** What [WebSearchResponseParser.parse] pulls out of a Responses body. */
internal class ParsedResponse(
  val text: String,
  val sources: List<WebSource>,
  /** Number of `web_search_call` items: one search each, and the unit the fee is charged in. */
  val searches: Int,
  val usage: SseUsage?,
  /** Meta's own `error` message when the response says it failed; the text is then not an answer. */
  val failure: String?,
)

/**
 * Reads the Responses API body by hand rather than through a strict model: the `output` array mixes
 * item types (`web_search_call`, `reasoning`, `message`, ...) and Meta adds fields over time. Anything
 * not recognised is skipped, never an exception. The shape is the documented one
 * (dev.meta.ai/docs/search-grounding, "Example response"): `output[]` items; a `message` item holds
 * `content[]` parts of type `output_text` with `text` and `annotations[]` of type `url_citation`.
 */
internal object WebSearchResponseParser {

  /** `null` when [body] is not a JSON object at all. */
  fun parse(body: String): ParsedResponse? {
    val root = parseObject(body) ?: return null

    val texts = mutableListOf<String>()
    val sources = LinkedHashMap<String, WebSource>()
    var searches = 0
    for (item in root.array("output")) {
      val obj = item as? JsonObject ?: continue
      when (obj.string("type")) {
        "web_search_call" -> searches++
        "message" -> for (part in obj.array("content")) {
          val partObj = part as? JsonObject ?: continue
          if (partObj.string("type") != "output_text") continue
          partObj.string("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
          for (annotation in partObj.array("annotations")) {
            val a = annotation as? JsonObject ?: continue
            if (a.string("type") != "url_citation") continue
            val url = a.string("url")?.trim().orEmpty()
            // Only web links: the model will print this as a link, and a `javascript:` or `file:` one is not a source.
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            sources.getOrPut(url) { WebSource(a.string("title")?.trim().orEmpty().ifEmpty { hostOf(url) }, url) }
          }
        }
      }
    }
    val failed = root.string("status") == "failed"
    val errorMessage = (root["error"] as? JsonObject)?.string("message")
    return ParsedResponse(
      text = texts.joinToString("\n\n"),
      sources = sources.values.toList(),
      searches = searches,
      usage = usageOf(root["usage"] as? JsonObject),
      failure = if (failed || errorMessage != null) (errorMessage ?: "unknown error") else null,
    )
  }

  /** `error.message` of Meta's error body `{"error":{"message","type","param","code"}}`, shortened; `null` if there is none. */
  fun errorMessage(body: String): String? =
    (parseObject(body)?.get("error") as? JsonObject)
      ?.string("message")?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotEmpty() }

  private fun parseObject(body: String): JsonObject? =
    try {
      Json.parseToJsonElement(body) as? JsonObject
    } catch (e: Exception) {
      null
    }

  private fun usageOf(usage: JsonObject?): SseUsage? {
    if (usage == null) return null
    val input = usage.int("input_tokens") ?: return null
    return SseUsage(
      promptTokens = input,
      completionTokens = usage.int("output_tokens") ?: 0,
      promptTokensDetails = (usage["input_tokens_details"] as? JsonObject)?.int("cached_tokens")?.let(::PromptTokensDetails),
    )
  }

  private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { url }

  private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

  private const val MAX_ERROR_CHARS = 200
}
