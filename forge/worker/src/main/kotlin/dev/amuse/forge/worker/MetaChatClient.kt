package dev.amuse.forge.worker

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One message of the conversation, in the OpenAI-compatible shape the Meta Model API takes. */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun tool(callId: String, text: String) = ChatMessage("tool", text, toolCallId = callId)
        fun assistant(reply: AssistantReply) =
            ChatMessage("assistant", reply.content, reply.toolCalls.ifEmpty { null })
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
) {
    @Serializable
    data class FunctionCall(val name: String, /** A JSON object, as a string - that is how the API sends it. */ val arguments: String)
}

/** A function the model may call: `{type: "function", function: {name, description, parameters}}`. */
@Serializable
data class ToolSchema(val type: String = "function", val function: Definition) {
    @Serializable
    data class Definition(val name: String, val description: String, val parameters: JsonObject)
}

/** What came back: the assistant's text and the tool calls it wants executed. */
data class AssistantReply(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
)

class MetaApiException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Calls Meta's OpenAI-compatible `chat/completions` endpoint.
 *
 * The body mirrors what the app's `OpenAiCompatClient` sends to the same API: snake_case fields,
 * `tools` as `{type: function, function: {...}}`, `max_tokens` 16000 (it covers reasoning AND the
 * answer), a top-level `reasoning_effort`, and never `temperature` together with `top_p` (Meta asks
 * for one of them). Two deliberate differences: `stream` is false - the worker has no UI to feed, and a
 * whole message is simpler to parse and to script in tests - and no `stream_options`.
 *
 * Errors become [MetaApiException] with the status and a short excerpt of the body. The key is scrubbed
 * from every message, in case a gateway echoes the request.
 */
@OptIn(ExperimentalSerializationApi::class)
class MetaChatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    engine: HttpClientEngine = CIO.create(),
    private val maxTokens: Int = 16_000,
    private val reasoningEffort: String? = null,
    private val temperature: Double? = null,
    private val topP: Double? = null,
    /** Waits before retrying a 429/5xx/network failure; empty disables retrying (tests). */
    private val retryDelaysMs: List<Long> = listOf(2_000, 6_000, 15_000),
    private val requestTimeoutMs: Long = 240_000,
) : AutoCloseable {
    init {
        // Meta documents one of the two, not both; fail loudly at construction instead of with a 400 mid-job.
        require(temperature == null || topP == null) { "Set temperature or top_p, never both" }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val http = HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = requestTimeoutMs
            this.requestTimeoutMillis = requestTimeoutMs
        }
    }

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<ChatMessage>,
        val tools: List<ToolSchema>? = null,
        val maxTokens: Int,
        val stream: Boolean = false,
        val temperature: Double? = null,
        val topP: Double? = null,
        val reasoningEffort: String? = null,
    )

    /** The exact JSON that goes over the wire; public so tests can look at the shape. */
    fun requestBody(messages: List<ChatMessage>, tools: List<ToolSchema>, withReasoning: Boolean = true): String =
        json.encodeToString(
            Request(
                model = model,
                messages = messages,
                tools = tools.ifEmpty { null },
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                reasoningEffort = reasoningEffort.takeIf { withReasoning },
            ),
        )

    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSchema>): AssistantReply {
        var withReasoning = reasoningEffort != null
        var attempt = 0
        while (true) {
            try {
                return send(requestBody(messages, tools, withReasoning))
            } catch (e: MetaApiException) {
                // The app does the same: a model that rejects the effort field gets the request once more without it.
                if (e.status == 400 && withReasoning) {
                    withReasoning = false
                    continue
                }
                val retryable = e.status == null || e.status == 429 || e.status in 500..599
                if (!retryable || attempt >= retryDelaysMs.size) throw e
                delay(retryDelaysMs[attempt++])
            }
        }
    }

    private suspend fun send(body: String): AssistantReply {
        val text = try {
            val response = http.post("${baseUrl.trimEnd('/')}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val payload = response.bodyAsText()
            if (response.status.value !in 200..299) {
                throw MetaApiException(response.status.value, "Meta API HTTP ${response.status.value}: ${excerpt(payload)}")
            }
            payload
        } catch (e: CancellationException) {
            throw e
        } catch (e: MetaApiException) {
            throw e
        } catch (e: Exception) {
            throw MetaApiException(null, scrub("Meta API request failed: ${e::class.simpleName}: ${e.message}"), e)
        }
        return parse(text)
    }

    /** Reads `choices[0].message`; tolerant of a missing `content`, a missing tool-call id and of `arguments` sent as an object. */
    internal fun parse(payload: String): AssistantReply {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw MetaApiException(null, "Meta API sent a reply that is not JSON: ${excerpt(payload)}", e)
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw MetaApiException(null, "Meta API reply has no choices: ${excerpt(payload)}")
        val message = choice["message"]?.jsonObject
            ?: throw MetaApiException(null, "Meta API reply has no message: ${excerpt(payload)}")
        val content = message["content"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val calls = message["tool_calls"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray.orEmpty()
            .mapIndexed { index, element ->
                val obj = element.jsonObject
                val function = obj["function"]?.jsonObject
                    ?: throw MetaApiException(null, "Meta API tool call has no function: ${excerpt(payload)}")
                val rawArguments: JsonElement? = function["arguments"]
                ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "call_$index",
                    function = ToolCall.FunctionCall(
                        name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        arguments = when (rawArguments) {
                            null, is kotlinx.serialization.json.JsonNull -> "{}"
                            is JsonPrimitive -> rawArguments.content.ifBlank { "{}" }
                            else -> rawArguments.toString()
                        },
                    ),
                )
            }
        return AssistantReply(content, calls, choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
    }

    private fun excerpt(text: String): String = scrub(text.replace(Regex("\\s+"), " ").take(500))

    private fun scrub(text: String): String = if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    override fun close() = http.close()
}
