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

@Inject
@SingleIn(AppScope::class)
class OpenAiCompatClient(
  private val fallbackChain: ProviderFallbackChain,
  /**
   * Учёт расхода. Без значения по умолчанию — намеренно, по тому же доводу, что и провайдер в
   * [LlmClient.streamChat]: заглушка «по умолчанию» превратила бы забытое связывание в тихо
   * неработающий учёт денег, а так граф просто не соберётся. Тесты передают
   * [NoOpLlmUsageRecorder] явно, и это заодно говорит читателю, что расход в них не проверяется.
   */
  private val usageRecorder: LlmUsageRecorder,
  // Движок инъектится (а не создаётся здесь), чтобы тест фолбэка подменял транспорт MockEngine'ом:
  // цепочку моделей нельзя проверить, не управляя ответами. Прод-движок — в LlmModule.
  engine: HttpClientEngine,
) : LlmClient {

  private val json = openAiCompatJson()

  private val httpClient = HttpClient(engine) {
    install(ContentNegotiation) {
      json(json)
    }
    // Стрим живёт долго, поэтому общий request-таймаут отключаем, но страхуемся от зависшего
    // соединения: connect — на установку, socket — на простой между SSE-пакетами.
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
    // Как только провайдер отдал хоть кусок ответа, переход к следующему склеил бы два разных
    // ответа в один. Поэтому после первого эмита фоллбэк запрещён.
    var emittedAnswer = false

    // Единая трасса хода: какие провайдеры в цепочке и сколько их (видно, есть ли вообще фолбэк).
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
          // Цену в долларах называет OpenRouter, и только когда её попросили. Другой провайдер
          // (следующим станет прямой Meta Model API) может этого поля не знать, а слать его туда —
          // просить строгий API отказать по неизвестному ключу; та же осторожность, что с
          // type:"function" у тулов.
          usage = if (p.name == LlmProvider.OPENROUTER) UsageRequest() else null,
        )
        val bodyJson = json.encodeToString(requestBody)
        // Ключевая проверка сериализации: type:"function" должен присутствовать у тулов, иначе строгие
        // провайдеры дают HTTP 400 «missing field type». Логируем явно, чтобы баг было видно сразу.
        val toolTypeOk = tools.isEmpty() || bodyJson.contains("\"type\":\"function\"")
        Log.i(
          TAG,
          "$attempt $tag → POST ${p.baseUrl} bytes=${bodyJson.length} maxTokens=${p.maxTokens} " +
            "toolTypePresent=$toolTypeOk",
        )

        val callStart = System.currentTimeMillis()
        // HTTP 200 ещё не значит «ответ»: провайдер умеет закрыть стрим, не прислав ни токена.
        var emptyAttempt = false
        httpClient.preparePost(p.baseUrl) {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          setBody(bodyJson)
        }.execute { response ->
          val status = response.status.value
          if (status !in 200..299) {
            val bodyText = response.body<String>()
            // Отказ в самом запросе (400/413) разбирают по телу ответа — там причина («слишком
            // длинный контекст», «модель не принимает картинки»), поэтому его пишем целиком.
            val logLimit = if (status.toString() in REQUEST_LEVEL_FAILURES) 4_000 else 800
            Log.w(TAG, "$attempt $tag ✗ HTTP $status: ${bodyText.replace("\n", " ").take(logLimit)}")
            // Бросаем, чтобы catch ниже перешёл к следующему провайдеру AUTO-цепочки.
            throw LlmHttpException(status, bodyText)
          }
          Log.i(TAG, "$attempt $tag ← HTTP $status, streaming…")

          // Счётчики по итогу стрима — сразу видно, ПУСТОЙ ли ответ и почему (нет content? только reasoning?).
          var textChars = 0
          var toolCalls = 0
          var finishReason = "?"
          // Ошибку, которую нашёл сам разбор стрима (ответ обрезан по max_tokens — см. consumeOpenAiSse),
          // придерживаем до конца попытки, как и Finish: она терминальная для хода.
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
              // Finish придерживаем до конца попытки: он терминальный, а пустая попытка уходит в
              // фолбэк — потребитель не должен увидеть конец хода раньше ответа следующей модели.
              is StreamEvent.Finish -> finishReason = event.reason
              is StreamEvent.Error -> streamError = event
            }
          }
          val ms = System.currentTimeMillis() - callStart
          // Ошибка разбора — не «пустая попытка»: модель ответила, но обрезанно, и следующая модель
          // цепочки упрётся в тот же max_tokens. Ход кончается ошибкой с «Повторить», без фолбэка.
          emptyAttempt = textChars == 0 && toolCalls == 0 && streamError == null
          Log.i(
            TAG,
            "$attempt $tag ✓ done in ${ms}ms textChars=$textChars toolCalls=$toolCalls finish=$finishReason" +
              (if (emptyAttempt) "  ⚠ EMPTY RESPONSE" else "") +
              (streamError?.let { "  ✗ ${it.code}" } ?: ""),
          )
          // Пишем расход и для ПУСТОЙ попытки тоже: промпт у неё улетел целиком и оплачен, а
          // ответа не было. Пропускать такие значило бы прятать самую дорогую разновидность
          // потерь — деньги за деградировавшую модель, которую цепочка молча заменила следующей.
          usageRecorder.record(
            LlmCallUsage(
              provider = p.name,
              model = p.model,
              // Тело запроса как мера промпта: в нём и системная часть, и история, и схемы тулов —
              // сумма длин сообщений недосчитала бы ровно то, что дороже всего.
              promptTokens = sseUsage?.promptTokens ?: LlmContextConfig.estimateTokens(bodyJson.length),
              completionTokens = sseUsage?.completionTokens ?: LlmContextConfig.estimateTokens(textChars),
              costUsd = sseUsage?.cost,
              estimated = sseUsage == null,
              hadTools = tools.isNotEmpty(),
            ),
          )
          // finish_reason "error" — OpenRouter так закрывает стрим, когда модель упала посреди ответа.
          // Часть текста уже у пользователя, и обычный Finish сохранил бы обрывок как готовый ответ;
          // ошибка даёт «Повторить» (фолбэк здесь нельзя — см. emittedAnswer ниже).
          val parseError = streamError
          when {
            parseError != null -> emit(parseError)
            emptyAttempt -> Unit
            finishReason == "error" ->
              emit(StreamEvent.Error(userFacingLlmError(null, interrupted = true), code = "finish_error"))
            else -> emit(StreamEvent.Finish(finishReason))
          }
        }
        if (emptyAttempt) {
          // Ни текста, ни тул-колла при HTTP 200 — это не ответ, а молчание (живой случай: NIM так
          // отдаёт деградировавшую модель). Берём следующую модель вместо того, чтобы закончить ход
          // пустым сообщением, имея в цепочке живые модели.
          lastError = IllegalStateException("Empty response from $tag")
          lastErrorCode = "empty_response"
          continue
        }
        return@flow
      } catch (e: CancellationException) {
        // Отмена — НЕ отказ провайдера. `CancellationException` наследует `Exception`, поэтому без
        // этой ветки Stop (или новый ход, гасящий предыдущий) ловился бы общим catch ниже, засчитывался
        // как «модель не ответила» и прогонял остаток цепочки, чтобы в конце эмитнуть
        // «All providers failed» из уже отменённого flow. Тот же порядок веток, что и в ToolExecutor.
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "$attempt $tag failed: ${e.message}")
        lastError = e
        // Всегда переписываем оба поля вместе — иначе на не-HTTP ошибке (таймаут и т.п.) следующего
        // провайдера lastErrorCode остался бы от ПРЕДЫДУЩЕГО провайдера и разошёлся бы с lastError.
        lastErrorCode = (e as? LlmHttpException)?.status?.toString()
        if (emittedAnswer) {
          // Часть ответа уже ушла потребителю — молча перезапускать на другом провайдере нельзя,
          // иначе в чат попадёт обрывок + новый полный ответ.
          Log.w(TAG, "$tag interrupted mid-stream — НЕ фолбэчим (часть ответа уже у пользователя)")
          emit(StreamEvent.Error(userFacingLlmError(lastErrorCode, interrupted = true), code = lastErrorCode))
          return@flow
        }
        // Ключ не подошёл или на счёте пусто — это отказ ключа, а не модели: следующая модель того же
        // провайдера получит тот же ответ. Перебирать цепочку дальше — четыре лишних запроса и минута
        // ожидания ради той же ошибки.
        if (lastErrorCode in KEY_LEVEL_FAILURES) break
        // 400/413 — отказ в самом ЗАПРОСЕ (слишком длинный контекст, неподдерживаемое вложение,
        // кривое тело): его получит и следующая модель. Раньше цепочка проходила все пять моделей и
        // заканчивалась «нет сети» — и неправдой, и минутой ожидания.
        if (lastErrorCode in REQUEST_LEVEL_FAILURES) break
        continue
      }
    }

    Log.e(TAG, "✗ LLM turn: ВСЕ провайдеры упали. Last code=$lastErrorCode msg=${lastError?.message}")
    emit(StreamEvent.Error(userFacingLlmError(lastErrorCode, interrupted = false), code = lastErrorCode))
  }

  @Serializable
  private data class OpenAiChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<FunctionSchema>? = null,
    // namingStrategy = SnakeCase маппит maxTokens -> max_tokens. Бэкстоп, если ProviderConfig
    // почему-то не задал значение; обычно приходит из ProviderConfig.maxTokens.
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    // null omitted via explicitNulls=false — providers without an override fall back to their own default.
    val temperature: Double? = null,
    val topP: Double? = null,
    /**
     * Просим провайдера прислать расход последним чанком стрима. Поле стандартное для
     * OpenAI-совместимого API (`stream_options.include_usage`), и шлём его всегда: без него usage
     * в стриме не приходит вовсе, а считать расход по оценке там, где провайдер готов назвать
     * точное число, — терять единственный достоверный источник данных для тарифов.
     */
    val streamOptions: StreamOptions = StreamOptions(),
    /** Только OpenRouter — см. место заполнения. */
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

// OpenAI-совместимый протокол использует snake_case (tool_calls, finish_reason, tool_call_id).
// explicitNulls = false, чтобы не слать null-поля (tool_calls/content) в запросе.
// encodeDefaults = true ОБЯЗАТЕЛЕН: без него поля со значением по умолчанию выпадают из запроса —
// в частности type="function" у каждого тула и у tool_call. Лояльные модели это глотают, но строгие
// (llama/gpt-oss/nemotron на NIM) отвечают HTTP 400 «missing field `type`», ломая фолбэк при rate-limit.
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
  // Расход хода. Приезжает отдельным чанком в конце стрима, поэтому копится вне цикла.
  var usage: SseUsage? = null
  // Reasoning-модели стримят размышление отдельным полем (OpenRouter, в том числе для Muse Spark, —
  // delta.reasoning; прямые OpenAI-совместимые API — reasoning_content), а ответ — в content. Копим размышление отдельно: если у хода content так и не пришёл (модель уложила всё в
  // reasoning) и не было tool-call, отдаём размышление как ответ — иначе пользователь видит пустоту.
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
    // Строго ДО проверки choices ниже: usage приезжает отдельным финальным чанком, у которого
    // choices пуст, и `firstOrNull() ?: continue` выбрасывал его вместе с чанком — расход хода
    // оставался неизвестен, хотя провайдер его прислал. Присваиваем и продолжаем: за usage-чанком
    // идёт ещё [DONE], да и порядок чанков провайдер не обещает.
    sseChunk.usage?.let { usage = it }
    val choice = sseChunk.choices.firstOrNull() ?: continue
    choice.delta?.content?.let {
      // Пустая content-дельта (role-priming чанк «content:""» в начале стрима) текста не несёт, но
      // во внешнем цикле выставила бы emittedAnswer=true и запретила фолбэк ещё до реального ответа —
      // при обрыве сразу после неё ход умер бы на пустом экране. Пропускаем пустые дельты целиком.
      if (it.isNotEmpty()) {
        contentSeen = true
        emit(StreamEvent.TextDelta(it))
      }
    }
    (choice.delta?.reasoningContent ?: choice.delta?.reasoning)?.let { reasoning.append(it) }
    // Tool-call приходит фрагментами по нескольким дельтам — копим по index.
    choice.delta?.toolCalls?.forEach { tc ->
      val idx = tc.index ?: 0
      val acc = toolAccumulators.getOrPut(idx) { ToolCallAccumulator(idx) }
      tc.id?.let { acc.id = it }
      tc.function?.name?.let { acc.name = it }
      tc.function?.arguments?.let { acc.arguments.append(it) }
    }
    choice.finishReason?.let { finishReason = it }
  }

  // finish_reason "length" — модель упёрлась в max_tokens. У reasoning-моделей это чаще всего значит,
  // что бюджет съело размышление, а ответ так и не начался. Раньше такой ответ считался готовым:
  // обрывок уходил в чат как полноценный, а недописанный tool-call исполнялся с `{}` вместо аргументов.
  val truncated = finishReason == FINISH_LENGTH
  // Аргументы разбираем ДО эмита: при обрезке последний вызов может оказаться недописанным, и тогда
  // нельзя отдавать ни его, ни соседние — ход должен закончиться ошибкой, а не половиной действий.
  val assembledCalls = toolAccumulators.values.map { acc ->
    acc to parseToolArgumentsOrNull(acc.arguments.toString(), json, onParseError)
  }
  if (truncated && assembledCalls.any { (acc, args) -> args == null || acc.name.isBlank() }) {
    emit(StreamEvent.Error(userFacingLlmError(CODE_LENGTH_TOOL_CALL, interrupted = false), code = CODE_LENGTH_TOOL_CALL))
    return usage
  }
  if (truncated && !contentSeen && assembledCalls.isEmpty()) {
    // Размышление здесь НЕ отдаём ответом: оно оборвано на полуслове так же, как ответ.
    emit(StreamEvent.Error(userFacingLlmError(CODE_LENGTH_EMPTY, interrupted = false), code = CODE_LENGTH_EMPTY))
    return usage
  }

  // Фолбэк: модель выдала только reasoning_content (без content и без tool-call) — отдаём его, чтобы
  // ход не закончился немым экраном, но с пометкой: без неё размышление вслух («пользователь хочет…»)
  // читалось как странный ответ. Флаг reasoning — для тех, кто ждёт JSON (см. StreamEvent.TextDelta).
  if (!contentSeen && assembledCalls.isEmpty() && reasoning.isNotBlank()) {
    emit(StreamEvent.TextDelta(REASONING_ONLY_PREFIX + reasoning.toString().trim(), reasoning = true))
  }

  // Эмитим каждый собранный tool-call один раз, с полными аргументами. Нечитаемые аргументы без
  // обрезки (модель ошиблась в JSON) — пустая карта, как и раньше: тул ответит «нет аргумента», и
  // модель поправится на следующем витке.
  for ((acc, args) in assembledCalls) {
    emit(
      StreamEvent.ToolCall(
        id = acc.id ?: acc.index.toString(),
        name = acc.name,
        arguments = args ?: emptyMap(),
      ),
    )
  }
  // Текстовый ответ, обрезанный по длине, — не ошибка: сказано почти всё, и переспрашивать заново
  // дороже. Но пользователь должен видеть, что конец потерян, а не думать, что модель так и закончила.
  if (truncated && contentSeen && assembledCalls.isEmpty()) {
    emit(StreamEvent.TextDelta(TRUNCATED_ANSWER_SUFFIX))
  }
  // Некоторые провайдеры закрывают поток без finish_reason — считаем это "stop",
  // иначе агент-цикл крутится до MAX_ITERATIONS и теряет готовый ответ.
  emit(StreamEvent.Finish(finishReason ?: "stop"))
  return usage
}

private const val FINISH_LENGTH = "length"

/** Маркеры обрезки по max_tokens — коды [StreamEvent.Error], по ним же [userFacingLlmError] выбирает текст. */
internal const val CODE_LENGTH_EMPTY = "length_empty"
internal const val CODE_LENGTH_TOOL_CALL = "length_tool_call"

/** Пометка над размышлением, отданным вместо ответа: читается как мысли модели, а не её ответ. */
internal const val REASONING_ONLY_PREFIX = "💭 *Ответа нет — вот ход мыслей модели:*\n\n"

/** Хвост ответа, обрезанного по длине. */
internal const val TRUNCATED_ANSWER_SUFFIX = "…\n\n*(ответ обрезан)*"

private class ToolCallAccumulator(val index: Int) {
  var id: String? = null
  var name: String = ""
  val arguments = StringBuilder()
}

/** Аргументы tool-call'а; null — JSON не разобрался (недописан или битый), решает вызывающий. */
private fun parseToolArgumentsOrNull(
  args: String,
  json: Json,
  onError: (Throwable) -> Unit = {},
): Map<String, JsonElement>? =
  try {
    // Пустая строка тоже null: при обрезке по длине это вызов, оборванный сразу после имени.
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
 * Расход одного вызова так, как его назвал сам провайдер.
 *
 * Отсутствие usage целиком (`consumeOpenAiSse` возвращает `null`) и нули в полях — РАЗНЫЕ
 * утверждения, и различать их обязан тип: `null` значит «провайдер не сказал», и вызывающий берёт
 * оценку по длине текста, помечая её приблизительной; нули значили бы «ход был бесплатным». Слить
 * их в одно число — получить учёт, в котором молчание провайдера неотличимо от нулевого счёта.
 */
@Serializable
internal data class SseUsage(
  val promptTokens: Int = 0,
  val completionTokens: Int = 0,
  /** Цена вызова в USD. Присылает OpenRouter, и только когда её попросили; иначе — null. */
  val cost: Double? = null,
)

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
  // Размышление reasoning-моделей: прямые OpenAI-совместимые API шлют reasoning_content, OpenRouter —
  // reasoning. Читаем оба: без второго фолбэк «размышление как ответ» на OpenRouter не срабатывал
  // никогда, и ход, уложившийся в одно размышление, жёг пять попыток по моделям цепочки.
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

/**
 * Текст ошибки хода для человека: его показывают в чате и в пуше о неудачном фоновом ходе.
 *
 * Раньше туда уходило «All providers failed. Last: HTTP 401: {json…}» — английский и сырой JSON
 * провайдера в русском приложении, по которому не понять, что делать. Подробности остаются в логе;
 * здесь — что случилось и что поправить. [code] — HTTP-статус последней попытки или наш маркер
 * (`blank_key`, `empty_response`).
 */
/** HTTP-статусы, при которых виноват ключ или счёт, а не модель (см. фолбэк в streamChat). */
private val KEY_LEVEL_FAILURES = setOf("401", "402", "403")

/** HTTP-статусы, при которых провайдер отверг сам запрос: другая модель цепочки отвергнет его так же. */
private val REQUEST_LEVEL_FAILURES = setOf("400", "413")

internal fun userFacingLlmError(code: String?, interrupted: Boolean): String = when {
  code == "blank_key" -> "Не задан ключ OpenRouter — впиши его в Настройках."
  code == "401" || code == "403" -> "Ключ OpenRouter не подошёл — проверь его в Настройках."
  code == "402" -> "На счёте OpenRouter закончились средства."
  code == "429" -> "Слишком много запросов к модели — попробуй через минуту."
  code in REQUEST_LEVEL_FAILURES ->
    "Модель отклонила запрос: возможно, переписка слишком длинная или вложение ей не подходит. " +
      "Попробуй короче или начни новый чат."
  code == CODE_LENGTH_EMPTY ->
    "Модель слишком долго размышляла и не успела ответить. Нажми «Повторить»."
  code == CODE_LENGTH_TOOL_CALL ->
    "Ответ модели оборвался посреди действия — ничего не выполнено. Нажми «Повторить»."
  interrupted -> "Ответ оборвался: связь с моделью прервалась. Нажми «Повторить»."
  code == "empty_response" -> "Модель ответила пустотой. Попробуй ещё раз."
  else -> "Модель не ответила: нет сети или сбой у провайдера. Попробуй ещё раз."
}
