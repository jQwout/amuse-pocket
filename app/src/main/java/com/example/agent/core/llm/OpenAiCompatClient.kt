package com.aiopenpocket.agent.core.llm

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
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

@OptIn(ExperimentalSerializationApi::class)
@Inject
@SingleIn(AppScope::class)
class OpenAiCompatClient(
  private val fallbackChain: ProviderFallbackChain,
) : LlmClient {

  // OpenAI-совместимый протокол использует snake_case (tool_calls, finish_reason,
  // tool_call_id). Naming strategy маппит camelCase-поля DTO в обе стороны.
  // explicitNulls = false, чтобы не слать null-поля (tool_calls/content) в запросе.
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = false
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val httpClient = HttpClient(Android) {
    install(ContentNegotiation) {
      json(json)
    }
  }

  override fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider,
  ): Flow<StreamEvent> = flow {
    val providers = fallbackChain.resolve(provider)
    var lastError: Exception? = null

    for (p in providers) {
      try {
        val apiKey = p.apiKeyResolver()
        if (apiKey.isBlank()) {
          lastError = IllegalStateException("API key for ${p.name} is blank")
          continue
        }

        val requestBody = OpenAiChatRequest(
          model = p.model,
          messages = messages,
          tools = tools.ifEmpty { null },
          maxTokens = p.maxTokens,
          stream = true,
        )

        httpClient.preparePost(p.baseUrl) {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          setBody(json.encodeToString(requestBody))
        }.execute { response ->
          if (response.status.value !in 200..299) {
            val bodyText = response.body<String>()
            // Бросаем, чтобы catch ниже перешёл к следующему провайдеру AUTO-цепочки.
            throw IllegalStateException("HTTP ${response.status.value}: $bodyText")
          }

          val channel = response.body<io.ktor.utils.io.ByteReadChannel>()
          val toolAccumulators = LinkedHashMap<Int, ToolCallAccumulator>()
          var finishReason: String? = null
          while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: continue
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue

            val sseChunk = try {
              json.decodeFromString<SseChunk>(data)
            } catch (e: Exception) {
              emit(StreamEvent.Error("Parse error: ${e.message}"))
              continue
            }
            val choice = sseChunk.choices.firstOrNull() ?: continue
            choice.delta?.content?.let { emit(StreamEvent.TextDelta(it)) }
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

          // Эмитим каждый собранный tool-call один раз, с полными аргументами.
          for (acc in toolAccumulators.values) {
            emit(
              StreamEvent.ToolCall(
                id = acc.id ?: acc.index.toString(),
                name = acc.name,
                arguments = parseArguments(acc.arguments.toString()),
              ),
            )
          }
          // Некоторые провайдеры закрывают поток без finish_reason — считаем это "stop",
          // иначе агент-цикл крутится до MAX_ITERATIONS и теряет готовый ответ.
          emit(StreamEvent.Finish(finishReason ?: "stop"))
        }
        return@flow
      } catch (e: Exception) {
        Log.w("OpenAiCompatClient", "Provider ${p.name} failed: ${e.message}")
        lastError = e
        continue
      }
    }

    emit(StreamEvent.Error("All providers failed. Last: ${lastError?.message}"))
  }

  private class ToolCallAccumulator(val index: Int) {
    var id: String? = null
    var name: String = ""
    val arguments = StringBuilder()
  }

  private fun parseArguments(args: String): Map<String, JsonElement> {
    return try {
      json.decodeFromString<JsonObject>(args).toMap()
    } catch (e: Exception) {
      emptyMap()
    }
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
  )

  @Serializable
  private data class SseChunk(
    val id: String? = null,
    val choices: List<SseChoice> = emptyList(),
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
}
