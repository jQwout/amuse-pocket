package dev.amuse.forge.gateway.ai

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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

// @trace market-ai-8f3b21

class GeneratedImage(val bytes: ByteArray, val mediaType: String)

class MetaCallException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The two Meta calls the gateway makes; an interface so tests script the answers. */
interface MetaModels {
    val chatModel: String

    /** One system + one user message, the answer's text. [effort] is Meta's `reasoning_effort`. */
    suspend fun complete(system: String, user: String, effort: String? = null): String

    suspend fun image(prompt: String): GeneratedImage
}

/**
 * The Meta Model API over HTTP, with the same request shapes the app and the worker already send:
 * Chat Completions without streaming, and Muse Image with exactly its five fields (Meta answers 400
 * to anything else). The key is scrubbed from every error message.
 */
@OptIn(ExperimentalSerializationApi::class)
class HttpMetaModels(
    private val baseUrl: String,
    private val apiKey: String,
    override val chatModel: String = "muse-spark-1.3",
    private val imageModel: String = "muse-image-1.0",
    engine: HttpClientEngine = CIO.create(),
    private val maxTokens: Int = 8_000,
    requestTimeoutMs: Long = 120_000,
) : MetaModels, AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val http = HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = requestTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
        }
    }

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val maxTokens: Int,
        val stream: Boolean = false,
        val reasoningEffort: String? = null,
    )

    @Serializable
    private data class ImageRequest(
        val model: String,
        val prompt: String,
        val size: String,
        val responseFormat: String,
        val outputFormat: String,
    )

    override suspend fun complete(system: String, user: String, effort: String?): String {
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(chatModel, listOf(Message("system", system), Message("user", user)), maxTokens, reasoningEffort = effort),
        )
        val payload = post("chat/completions", body)
        val message = runCatching {
            json.parseToJsonElement(payload).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject
        }.getOrElse { throw MetaCallException(null, "Meta chat answer has no message: ${excerpt(payload)}", it) }
        return message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw MetaCallException(null, "Meta chat answer has no text (only reasoning, or empty)")
    }

    override suspend fun image(prompt: String): GeneratedImage {
        val body = json.encodeToString(
            ImageRequest.serializer(),
            ImageRequest(imageModel, prompt, IMAGE_SIZE, "b64_json", IMAGE_FORMAT),
        )
        val payload = post("images/generations", body)
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw MetaCallException(null, "Meta image answer is not JSON: ${excerpt(payload)}", it) }
        val b64 = root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("b64_json")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: throw MetaCallException(null, "Meta image answer has no picture")
        // The answer names no media type; it says which format it used.
        val format = root["output_format"]?.jsonPrimitive?.contentOrNull ?: IMAGE_FORMAT
        return GeneratedImage(Base64.getDecoder().decode(b64), "image/$format")
    }

    private suspend fun post(path: String, body: String): String {
        val response = try {
            http.post("${baseUrl.trimEnd('/')}/$path") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            throw MetaCallException(null, "Meta API unreachable: ${scrub(e.message.orEmpty())}", e)
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw MetaCallException(response.status.value, "Meta API HTTP ${response.status.value}: ${excerpt(text)}")
        }
        return text
    }

    private fun excerpt(text: String) = scrub(text.replace(Regex("\\s+"), " ").take(300))

    private fun scrub(text: String) = if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    override fun close() = http.close()

    private companion object {
        /** 16:9, close to the 1.91:1 link previews use; the size the app's lesson images already use. */
        const val IMAGE_SIZE = "1280x720"
        const val IMAGE_FORMAT = "png"
    }
}

/**
 * The first balanced `{...}` in a model's answer, skipping prose and code fences around it. Braces
 * inside JSON strings are ignored, so a quote like `"}"` does not end the object early.
 */
fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return text.substring(start, i + 1)
        }
    }
    return null
}
