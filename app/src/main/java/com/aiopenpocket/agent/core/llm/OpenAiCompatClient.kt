package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1 flow:chat

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr

@Inject
@SingleIn(AppScope::class)
class OpenAiCompatClient(
  private val fallbackChain: ProviderFallbackChain,
  /**
   * Spend accounting. No default value on purpose, for the same reason as the provider in
   * [LlmClient.streamChat]: a "default" stub would turn a forgotten binding into quietly broken money
   * accounting, while this way the graph simply does not build. Tests pass [NoOpLlmUsageRecorder]
   * explicitly, which also tells the reader that spend is not checked in them.
   */
  private val usageRecorder: LlmUsageRecorder,
  // The engine is injected (not created here) so the fallback test can swap the transport for a
  // MockEngine: the model chain cannot be tested without controlling the responses. The production
  // engine is in LlmModule.
  engine: HttpClientEngine,
) : LlmClient {

  private val json = openAiCompatJson()

  private val httpClient = HttpClient(engine) {
    install(ContentNegotiation) {
      json(json)
    }
    // A stream lives long, so the overall request timeout is off, but we guard against a hung
    // connection: connect covers establishing it, socket covers idle time between SSE packets.
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      socketTimeoutMillis = SOCKET_IDLE_TIMEOUT_MS
      requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
    }
  }

  override fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider,
  ): Flow<StreamEvent> = flow {
    val providers = fallbackChain.resolve(provider)
    var lastError: Exception? = null
    var lastErrorCode: String? = if (providers.isEmpty()) "no_provider" else null
    // As soon as a provider has sent any part of an answer, moving on to the next one would glue two
    // different answers together. So after the first emit the fallback is forbidden.
    var emittedAnswer = false

    // A single trace for the turn: which providers are in the chain and how many (shows whether there is any fallback at all).
    Log.i(
      TAG,
      "▶ LLM turn requested=$provider chain=[${providers.joinToString { "${it.name}/${it.model}" }}] " +
        "msgs=${messages.size} tools=${tools.size}",
    )

    for ((idx, p) in providers.withIndex()) {
      val tag = "${p.name}/${p.model}"
      val attempt = "[${idx + 1}/${providers.size}]"
      try {
        val apiKey = p.apiKeyResolver()
        if (apiKey.isBlank()) {
          Log.w(TAG, "$attempt $tag → SKIP: API key is blank")
          lastError = IllegalStateException("API key for ${p.name} is blank")
          lastErrorCode = "blank_key"
          continue
        }

        val requestBody = OpenAiChatRequest(
          model = p.model,
          messages = messages,
          tools = tools.ifEmpty { null },  // null omitted via explicitNulls=false; [] would confuse some providers
          maxTokens = p.maxTokens,
          stream = true,
          temperature = p.temperature,
          topP = p.topP,
          // OpenRouter names the price in dollars, and only when asked. The direct Meta Model API request
          // has no such field, and Meta answers 400 to unsupported fields (the protocol description
          // lists a whole set of them); the same caution as with type:"function" on tools. The Meta
          // price is computed by us — see metaListPriceUsd.
          usage = if (p.name == LlmProvider.OPENROUTER) UsageRequest() else null,
        )
        val bodyJson = json.encodeToString(requestBody)
        // The key serialization check: tools must carry type:"function", otherwise strict providers return
        // HTTP 400 "missing field type". Logged explicitly so the bug is visible at once.
        val toolTypeOk = tools.isEmpty() || bodyJson.contains("\"type\":\"function\"")
        Log.i(
          TAG,
          "$attempt $tag → POST ${p.baseUrl} bytes=${bodyJson.length} maxTokens=${p.maxTokens} " +
            "toolTypePresent=$toolTypeOk",
        )

        val callStart = System.currentTimeMillis()
        // HTTP 200 does not yet mean "an answer": a provider can close the stream without sending a single token.
        var emptyAttempt = false
        httpClient.preparePost(p.baseUrl) {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          setBody(bodyJson)
        }.execute { response ->
          val status = response.status.value
          if (status !in 200..299) {
            val bodyText = response.body<String>()
            // A rejection of the request itself (400/413) is diagnosed from the response body — the reason
            // is there ("context too long", "the model does not accept images"), so it is logged in full.
            val logLimit = if (status.toString() in REQUEST_LEVEL_FAILURES) 4_000 else 800
            Log.w(TAG, "$attempt $tag ✗ HTTP $status: ${bodyText.replace("\n", " ").take(logLimit)}")
            // Throw so the catch below moves on to the next provider of the AUTO chain.
            throw LlmHttpException(status, bodyText)
          }
          Log.i(TAG, "$attempt $tag ← HTTP $status, streaming…")

          // Counters for the stream's outcome — shows at once whether the answer is EMPTY and why (no content? only reasoning?).
          var textChars = 0
          var toolCalls = 0
          var finishReason = "?"
          // An error found by the stream parsing itself (the answer was cut at max_tokens — see
          // consumeOpenAiSse) is held until the end of the attempt, like Finish: it is terminal for the turn.
          var streamError: StreamEvent.Error? = null
          val channel = response.body<ByteReadChannel>()
          val sseUsage = consumeOpenAiSse(
            channel = channel,
            json = json,
            onParseError = { Log.w(TAG, "$tag skipping unparseable SSE chunk: ${it.message}") },
          ) { event ->
            when (event) {
              is StreamEvent.TextDelta -> {
                textChars += event.content.length
                emittedAnswer = true
                emit(event)
              }
              is StreamEvent.ToolCall -> {
                toolCalls++
                emittedAnswer = true
                Log.d(TAG, "$tag tool_call: ${event.name}")
                emit(event)
              }
              // Finish is held until the end of the attempt: it is terminal, and an empty attempt goes to
              // the fallback — the consumer must not see the end of the turn before the next model's answer.
              is StreamEvent.Finish -> finishReason = event.reason
              is StreamEvent.Error -> streamError = event
            }
          }
          val ms = System.currentTimeMillis() - callStart
          // A parse error is not an "empty attempt": the model answered, but truncated, and the next model of
          // the chain would hit the same max_tokens. The turn ends with an error and "Retry", no fallback.
          emptyAttempt = textChars == 0 && toolCalls == 0 && streamError == null
          Log.i(
            TAG,
            "$attempt $tag ✓ done in ${ms}ms textChars=$textChars toolCalls=$toolCalls finish=$finishReason" +
              (if (emptyAttempt) "  ⚠ EMPTY RESPONSE" else "") +
              (streamError?.let { "  ✗ ${it.code}" } ?: ""),
          )
          // Spend is recorded for an EMPTY attempt too: its whole prompt went out and was paid for, and
          // there was no answer. Skipping those would hide the most expensive kind of loss — money
          // spent on a degraded model that the chain silently replaced with the next one.
          usageRecorder.record(
            LlmCallUsage(
              provider = p.name,
              model = p.model,
              // The request body as the measure of the prompt: it holds the system part, the history and the
              // tool schemas — the sum of message lengths would undercount exactly what costs most.
              promptTokens = sseUsage?.promptTokens ?: LlmContextConfig.estimateTokens(bodyJson.length),
              completionTokens = sseUsage?.completionTokens ?: LlmContextConfig.estimateTokens(textChars),
              // The direct Meta Model API sends tokens only, without a price — we compute it from Meta's price
              // list. Otherwise the spend card would show tokens without money exactly on the route
              // where the credits the user paid for are being spent.
              costUsd = sseUsage?.cost
                ?: sseUsage?.takeIf { p.name == LlmProvider.META }?.let { metaListPriceUsd(p.model, it) },
              estimated = sseUsage == null,
              hadTools = tools.isNotEmpty(),
            ),
          )
          // finish_reason "error" — that is how OpenRouter closes a stream when the model fails mid-answer.
          // Part of the text is already with the user, and a normal Finish would save the fragment as
          // a finished answer; an error gives "Retry" (no fallback here — see emittedAnswer below).
          val parseError = streamError
          when {
            parseError != null -> emit(parseError)
            emptyAttempt -> Unit
            finishReason == "error" ->
              emit(StreamEvent.Error(userFacingLlmError(null, interrupted = true, provider = provider), code = "finish_error"))
            else -> emit(StreamEvent.Finish(finishReason))
          }
        }
        if (emptyAttempt) {
          // Neither text nor a tool call with HTTP 200 is not an answer but silence (a real case: NIM
          // served a degraded model like this). Take the next model instead of ending the turn with an
          // empty message while the chain still has live models.
          lastError = IllegalStateException("Empty response from $tag")
          lastErrorCode = "empty_response"
          continue
        }
        return@flow
      } catch (e: CancellationException) {
        // Cancellation is NOT a provider failure. `CancellationException` extends `Exception`, so without
        // this branch Stop (or a new turn cancelling the previous one) would be caught by the general
        // catch below, counted as "the model did not answer", and run the rest of the chain only to
        // emit "All providers failed" from an already cancelled flow. Same branch order as in
        // ToolExecutor.
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "$attempt $tag failed: ${e.message}")
        lastError = e
        // Always overwrite both fields together — otherwise on a non-HTTP error (a timeout etc.) of the next
        // provider lastErrorCode would remain from the PREVIOUS provider and disagree with lastError.
        lastErrorCode = (e as? LlmHttpException)?.status?.toString()
        if (emittedAnswer) {
          // Part of the answer has already gone to the consumer — silently restarting on another provider is
          // not allowed, or the chat would get a fragment plus a new full answer.
          Log.w(TAG, "$tag interrupted mid-stream — НЕ фолбэчим (часть ответа уже у пользователя)")
          emit(StreamEvent.Error(userFacingLlmError(lastErrorCode, interrupted = true, provider = provider), code = lastErrorCode))
          return@flow
        }
        // The key did not fit or the balance is empty — a key rejection, not a model one: the next model of
        // the same provider would get the same answer. Going on through the chain means four extra
        // requests and a minute of waiting for the same error.
        if (lastErrorCode in KEY_LEVEL_FAILURES) break
        // 400/413 is a rejection of the REQUEST itself (context too long, an unsupported attachment, a
        // malformed body): the next model would get it too. The chain used to go through all five
        // models and end with "no network" — both untrue and a minute of waiting.
        if (lastErrorCode in REQUEST_LEVEL_FAILURES) break
        continue
      }
    }

    Log.e(TAG, "✗ LLM turn: ВСЕ провайдеры упали. Last code=$lastErrorCode msg=${lastError?.message}")
    // `provider` is the route of EVERY attempt, not just the requested one: ProviderFallbackChain.resolve
    // returns configs of the requested provider only, and there is no cross-provider fallback. So a
    // 401/402 message can name this provider's key without tracking which attempt failed last.
    emit(StreamEvent.Error(userFacingLlmError(lastErrorCode, interrupted = false, provider = provider), code = lastErrorCode))
  }

  @Serializable
  private data class OpenAiChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<FunctionSchema>? = null,
    // namingStrategy = SnakeCase maps maxTokens -> max_tokens. A backstop in case ProviderConfig for some
    // reason did not set a value; normally it comes from ProviderConfig.maxTokens.
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    // null omitted via explicitNulls=false — providers without an override fall back to their own default.
    val temperature: Double? = null,
    val topP: Double? = null,
    /**
     * Asks the provider to send the spend as the last chunk of the stream. The field is standard for
     * an OpenAI-compatible API (`stream_options.include_usage`), and we always send it: without it no
     * usage arrives in a stream at all, and estimating spend where the provider is ready to name the
     * exact number means losing the only reliable data source for pricing.
     */
    val streamOptions: StreamOptions = StreamOptions(),
    /** OpenRouter only — see where it is filled in. */
    val usage: UsageRequest? = null,
  )

  @Serializable
  private data class StreamOptions(val includeUsage: Boolean = true)

  @Serializable
  private data class UsageRequest(val include: Boolean = true)

  private companion object {
    const val TAG = "OpenAiCompatClient"
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val SOCKET_IDLE_TIMEOUT_MS = 60_000L
  }
}

// The OpenAI-compatible protocol uses snake_case (tool_calls, finish_reason, tool_call_id).
// explicitNulls = false so that null fields (tool_calls/content) are not sent in a request.
// encodeDefaults = true is REQUIRED: without it fields with default values drop out of the request —
// in particular type="function" on every tool and tool_call. Lenient models swallow that, but strict
// ones (llama/gpt-oss/nemotron on NIM) answer HTTP 400 "missing field `type`", breaking the fallback on
// a rate limit.
@OptIn(ExperimentalSerializationApi::class)
internal fun openAiCompatJson(): Json = Json {
  ignoreUnknownKeys = true
  isLenient = true
  prettyPrint = false
  explicitNulls = false
  encodeDefaults = true
  namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * Reads an OpenAI-compatible SSE stream and forwards [StreamEvent]s via [emit]. Text deltas go out as
 * they arrive; tool calls arrive fragmented across deltas, so they are accumulated by index and
 * emitted once, fully assembled, after the stream ends.
 *
 * A single unparseable `data:` chunk is reported through [onParseError] and SKIPPED — it must never
 * abort the stream. (Surfacing it as an error once killed whole turns over a stray keep-alive line.)
 * Real transport failures still propagate as exceptions to the caller.
 */
internal suspend fun consumeOpenAiSse(
  channel: ByteReadChannel,
  json: Json,
  onParseError: (Throwable) -> Unit = {},
  emit: suspend (StreamEvent) -> Unit,
): SseUsage? {
  val toolAccumulators = LinkedHashMap<Int, ToolCallAccumulator>()
  var finishReason: String? = null
  // The turn's spend. It arrives as a separate chunk at the end of the stream, so it is accumulated outside the loop.
  var usage: SseUsage? = null
  // Reasoning models stream their thinking in a separate field (OpenRouter, including for Muse Spark —
  // delta.reasoning; direct OpenAI-compatible APIs, Meta Model API among them — reasoning_content), and
  // the answer in content. Thinking is accumulated separately: if the turn never got content (the
  // model put everything into reasoning) and there was no tool call, the thinking is returned as the
  // answer — otherwise the user sees nothing.
  var contentSeen = false
  val reasoning = StringBuilder()
  while (!channel.isClosedForRead) {
    val line = channel.readUTF8Line() ?: continue
    if (!line.startsWith("data:")) continue
    val data = line.removePrefix("data:").trim()
    if (data == "[DONE]") break
    if (data.isEmpty()) continue

    val sseChunk = try {
      json.decodeFromString<SseChunk>(data)
    } catch (e: Exception) {
      onParseError(e)
      continue
    }
    // Strictly BEFORE the choices check below: usage arrives as a separate final chunk whose choices
    // are empty, and `firstOrNull() ?: continue` used to throw it away together with the chunk — the
    // turn's spend stayed unknown although the provider had sent it. Assign and continue: [DONE]
    // still follows the usage chunk, and the provider does not promise the chunk order anyway.
    sseChunk.usage?.let { usage = it }
    val choice = sseChunk.choices.firstOrNull() ?: continue
    choice.delta?.content?.let {
      // An empty content delta (the role-priming chunk `content:""` at the start of the stream) carries no
      // text, but in the outer loop it would set emittedAnswer=true and forbid the fallback before any
      // real answer — a break right after it would kill the turn on an empty screen. Empty deltas are
      // skipped entirely.
      if (it.isNotEmpty()) {
        contentSeen = true
        emit(StreamEvent.TextDelta(it))
      }
    }
    (choice.delta?.reasoningContent ?: choice.delta?.reasoning)?.let { reasoning.append(it) }
    // A tool call arrives in fragments across several deltas — accumulate by index.
    choice.delta?.toolCalls?.forEach { tc ->
      val idx = tc.index ?: 0
      val acc = toolAccumulators.getOrPut(idx) { ToolCallAccumulator(idx) }
      tc.id?.let { acc.id = it }
      tc.function?.name?.let { acc.name = it }
      tc.function?.arguments?.let { acc.arguments.append(it) }
    }
    choice.finishReason?.let { finishReason = it }
  }

  // finish_reason "length" — the model hit max_tokens. For reasoning models this most often means the
  // thinking ate the budget and the answer never started. Such an answer used to count as finished:
  // the fragment went to the chat as a full answer, and a half-written tool call ran with `{}` instead
  // of arguments.
  val truncated = finishReason == FINISH_LENGTH
  // Arguments are parsed BEFORE emitting: on truncation the last call may be half-written, and then
  // neither it nor its neighbours may be returned — the turn must end with an error, not half the
  // actions.
  val assembledCalls = toolAccumulators.values.map { acc ->
    acc to parseToolArgumentsOrNull(acc.arguments.toString(), json, onParseError)
  }
  // provider = null in both: these two texts are about the model's token budget, not about a key or a
  // balance, so they name no provider either way (see userFacingLlmError) — and the parser is kept out
  // of the business of knowing which route it is reading.
  if (truncated && assembledCalls.any { (acc, args) -> args == null || acc.name.isBlank() }) {
    emit(StreamEvent.Error(userFacingLlmError(CODE_LENGTH_TOOL_CALL, interrupted = false, provider = null), code = CODE_LENGTH_TOOL_CALL))
    return usage
  }
  if (truncated && !contentSeen && assembledCalls.isEmpty()) {
    // The thinking is NOT returned as the answer here: it is cut mid-word, just like the answer.
    emit(StreamEvent.Error(userFacingLlmError(CODE_LENGTH_EMPTY, interrupted = false, provider = null), code = CODE_LENGTH_EMPTY))
    return usage
  }

  // Fallback: the model produced only reasoning_content (no content and no tool call) — return it so
  // the turn does not end on a mute screen, but with a marker: without it thinking aloud ("the user
  // wants…") read as a strange answer. The reasoning flag is for those who expect JSON (see
  // StreamEvent.TextDelta).
  if (!contentSeen && assembledCalls.isEmpty() && reasoning.isNotBlank()) {
    emit(StreamEvent.TextDelta(REASONING_ONLY_PREFIX + reasoning.toString().trim(), reasoning = true))
  }

  // Emit every assembled tool call once, with full arguments. Unreadable arguments without truncation
  // (the model made a JSON mistake) become an empty map, as before: the tool answers "argument
  // missing", and the model corrects itself on the next iteration.
  for ((acc, args) in assembledCalls) {
    emit(
      StreamEvent.ToolCall(
        id = acc.id ?: acc.index.toString(),
        name = acc.name,
        arguments = args ?: emptyMap(),
      ),
    )
  }
  // A text answer cut by length is not an error: almost everything was said, and asking again costs
  // more. But the user must see that the end is lost rather than think the model ended like that.
  if (truncated && contentSeen && assembledCalls.isEmpty()) {
    emit(StreamEvent.TextDelta(TRUNCATED_ANSWER_SUFFIX))
  }
  // Some providers close the stream without a finish_reason — we treat that as "stop", otherwise the
  // agent loop spins until MAX_ITERATIONS and loses a finished answer.
  emit(StreamEvent.Finish(finishReason ?: "stop"))
  return usage
}

private const val FINISH_LENGTH = "length"

/** Markers of truncation at max_tokens — [StreamEvent.Error] codes; [userFacingLlmError] picks the text by them too. */
internal const val CODE_LENGTH_EMPTY = "length_empty"
internal const val CODE_LENGTH_TOOL_CALL = "length_tool_call"

/**
 * Marker above thinking returned instead of an answer: it reads as the model's thoughts, not its answer.
 *
 * A getter, not a `const`: this text is glued onto [StreamEvent.TextDelta] and lands inside the answer
 * bubble itself, so an English reader met it in Russian. A value chosen once at class init would freeze
 * in whatever language the app started in — the same reason `ToolCatalogEntry.consequence` is a getter.
 */
internal val REASONING_ONLY_PREFIX: String
  get() = tr(
    "💭 *Ответа нет — вот ход мыслей модели:*\n\n",
    "💭 *No answer — here is the model's train of thought:*\n\n",
  )

/** Tail of an answer cut by length. A getter for the same reason as [REASONING_ONLY_PREFIX]. */
internal val TRUNCATED_ANSWER_SUFFIX: String
  get() = tr("…\n\n*(ответ обрезан)*", "…\n\n*(answer cut off)*")

private class ToolCallAccumulator(val index: Int) {
  var id: String? = null
  var name: String = ""
  val arguments = StringBuilder()
}

/** A tool call's arguments; null — the JSON did not parse (half-written or broken), the caller decides. */
private fun parseToolArgumentsOrNull(
  args: String,
  json: Json,
  onError: (Throwable) -> Unit = {},
): Map<String, JsonElement>? =
  try {
    // An empty string is null too: on truncation by length it is a call cut right after its name.
    json.decodeFromString<JsonObject>(args).toMap()
  } catch (e: Exception) {
    onError(e)
    null
  }

@Serializable
private data class SseChunk(
  val id: String? = null,
  val choices: List<SseChoice> = emptyList(),
  val usage: SseUsage? = null,
)

/**
 * The spend of one call as the provider itself named it.
 *
 * No usage at all (`consumeOpenAiSse` returns `null`) and zeros in the fields are DIFFERENT
 * statements, and the type must tell them apart: `null` means "the provider did not say", and the
 * caller takes an estimate from the text length, marking it approximate; zeros would mean "the turn
 * was free". Merging them into one number gives accounting in which a provider's silence is
 * indistinguishable from a zero bill.
 */
@Serializable
internal data class SseUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  /** Price of the call in USD. OpenRouter sends it, and only when asked; otherwise null. */
  val cost: Double? = null,
  /** How many prompt tokens came from the provider's cache: at Meta they are 8 times cheaper than regular ones. */
  val promptTokensDetails: PromptTokensDetails? = null,
)

@Serializable
internal data class PromptTokensDetails(val cachedTokens: Int = 0)

@Serializable
private data class SseChoice(
  val index: Int? = null,
  val delta: SseDelta? = null,
  val finishReason: String? = null,
)

@Serializable
private data class SseDelta(
  val role: String? = null,
  val content: String? = null,
  // Thinking of reasoning models: direct OpenAI-compatible APIs send reasoning_content, OpenRouter —
  // reasoning. Both are read: without the second, the "thinking as the answer" fallback never fired
  // on OpenRouter, and a turn that fit into a single thought burned five attempts across the chain's
  // models.
  val reasoningContent: String? = null,
  val reasoning: String? = null,
  val toolCalls: List<SseToolCall>? = null,
)

@Serializable
private data class SseToolCall(
  val index: Int? = null,
  val id: String? = null,
  val type: String? = null,
  val function: SseFunctionCall? = null,
)

@Serializable
private data class SseFunctionCall(
  val name: String? = null,
  val arguments: String? = null,
)

private class LlmHttpException(val status: Int, body: String) : Exception("HTTP $status: $body")

/** HTTP statuses where the key or the balance is at fault, not the model (see the fallback in streamChat). */
private val KEY_LEVEL_FAILURES = setOf("401", "402", "403")

/** HTTP statuses where the provider rejected the request itself: another model of the chain would reject it the same way. */
private val REQUEST_LEVEL_FAILURES = setOf("400", "413")

/**
 * The turn's error text for a person: it is shown in the chat (a red banner over the input) and in
 * the push about a failed background turn.
 *
 * It used to be "All providers failed. Last: HTTP 401: {json…}" — the provider's raw JSON, with no way
 * to tell what to do; then plain Russian literals, which an English reader got word for word («Модель
 * не ответила…» stood on the English demo path). The details stay in the log; here — what happened and
 * what to fix, in the interface language ([tr]), because this very string reaches the person.
 *
 * [code] is the HTTP status of the last attempt or our own marker (`blank_key`, `empty_response`).
 * [provider] is the route that last attempt actually went to, and it decides WHOSE key or balance a
 * key-level failure is about: both routes are live (see [LlmProvider]), and the text used to name
 * OpenRouter on both — a person who had entered a Meta Model API key was sent to the wrong site to
 * look for the wrong key. `null` means the route is unknown (an empty chain, or an error raised inside
 * the stream, where no provider is involved in the wording anyway) — then the message names neither
 * and stays true for both.
 */
internal fun userFacingLlmError(code: String?, interrupted: Boolean, provider: LlmProvider?): String = when {
  code == "blank_key" -> blankKeyMessage(provider)
  code == "401" || code == "403" -> rejectedKeyMessage(provider)
  code == "402" -> emptyBalanceMessage(provider)
  code == "429" -> tr(
    "Слишком много запросов к модели — попробуй через минуту.",
    "Too many requests to the model — give it a minute and try again.",
  )
  code in REQUEST_LEVEL_FAILURES -> tr(
    "Модель отклонила запрос: возможно, переписка слишком длинная или вложение ей не подходит. " +
      "Попробуй короче или начни новый чат.",
    "The model refused the request: the conversation may be too long, or the attachment unsupported. " +
      "Try a shorter message, or start a new chat.",
  )
  code == CODE_LENGTH_EMPTY -> tr(
    "Модель слишком долго размышляла и не успела ответить. Нажми «Повторить».",
    "The model spent its whole budget thinking and never got to an answer. Tap “Retry”.",
  )
  code == CODE_LENGTH_TOOL_CALL -> tr(
    "Ответ модели оборвался посреди действия — ничего не выполнено. Нажми «Повторить».",
    "The model's answer broke off in the middle of an action — nothing was carried out. Tap “Retry”.",
  )
  interrupted -> tr(
    "Ответ оборвался: связь с моделью прервалась. Нажми «Повторить».",
    "The answer broke off: the connection to the model dropped. Tap “Retry”.",
  )
  code == "empty_response" -> tr(
    "Модель ответила пустотой. Попробуй ещё раз.",
    "The model answered with nothing at all. Try again.",
  )
  else -> tr(
    "Модель не ответила: нет сети или сбой у провайдера. Попробуй ещё раз.",
    "No answer from the model: either there is no network, or the provider is down. Try again.",
  )
}

/** No key for the route the request went to. Named per route — see [userFacingLlmError] on why. */
private fun blankKeyMessage(provider: LlmProvider?): String = when (provider) {
  LlmProvider.META -> tr("Не задан ключ Meta Model API — впиши его в Настройках.", "No Meta Model API key yet — add it in Settings.")
  LlmProvider.OPENROUTER -> tr("Не задан ключ OpenRouter — впиши его в Настройках.", "No OpenRouter key yet — add it in Settings.")
  null -> tr("Не задан ключ провайдера модели — впиши его в Настройках.", "The model provider has no API key yet — add one in Settings.")
}

/** The key went out and came back rejected (401/403). */
private fun rejectedKeyMessage(provider: LlmProvider?): String = when (provider) {
  LlmProvider.META -> tr("Ключ Meta Model API не подошёл — проверь его в Настройках.", "Meta Model API turned the key down — check it in Settings.")
  LlmProvider.OPENROUTER -> tr("Ключ OpenRouter не подошёл — проверь его в Настройках.", "OpenRouter turned the key down — check it in Settings.")
  null -> tr("Ключ провайдера модели не подошёл — проверь его в Настройках.", "The model provider turned the key down — check it in Settings.")
}

/** The key is fine, the money is not (402). */
private fun emptyBalanceMessage(provider: LlmProvider?): String = when (provider) {
  LlmProvider.META -> tr("На счёте Meta Model API закончились средства.", "The Meta Model API account has run out of credit.")
  LlmProvider.OPENROUTER -> tr("На счёте OpenRouter закончились средства.", "The OpenRouter account has run out of credit.")
  null -> tr("На счёте у провайдера модели закончились средства.", "The account at the model provider has run out of credit.")
}
