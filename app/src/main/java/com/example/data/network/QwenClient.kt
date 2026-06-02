package com.example.data.network

import android.util.Log
import com.example.data.database.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class QwenClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private val baseUrl = "https://chat.qwen.ai"
    private val defaultModel = "qwen3.6-plus"

    private var activeChatId: String? = null

    fun clearSession() {
        activeChatId = null
    }

    suspend fun generateResponse(
        cookies: String,
        history: List<ChatMessage>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) {
            return@withContext "Ошибка: сессия не найдена. Установите куки и повторите."
        }

        try {
            val chatId = activeChatId ?: createChat(cookies).also { activeChatId = it }
            val responseText = sendCompletion(cookies, chatId, userMessage)
            responseText.ifBlank { "Пустой ответ от Qwen." }
        } catch (e: Exception) {
            Log.e(TAG, "Qwen request failed", e)
            activeChatId = null
            "Ошибка: ${e.message ?: "Сетевой сбой при общении с Qwen."}"
        }
    }

    private fun extractTokenFromCookies(cookies: String): String? {
        val parts = cookies.split(";").map { it.trim() }
        for (part in parts) {
            val key = part.substringBefore("=").trim()
            val value = part.substringAfter("=", "").trim()
            if (key == "token") {
                return value
            }
        }
        return null
    }

    private suspend fun createChat(cookies: String): String {
        val createBody = CreateChatRequest(
            title = "New Chat",
            models = listOf(defaultModel),
            chat_mode = "normal",
            chat_type = "t2t",
            timestamp = System.currentTimeMillis(),
            project_id = ""
        )

        val token = extractTokenFromCookies(cookies)

        val response: HttpResponse = httpClient.post("$baseUrl/api/v2/chats/new") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, cookies)
            if (!token.isNullOrEmpty()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            header("source", "web")
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            header("x-request-id", UUID.randomUUID().toString())
            setBody(createBody)
        }

        val createRaw = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw QwenApiException("Create chat failed (${response.status.value}): $createRaw")
        }

        val createJson = json.parseToJsonElement(createRaw).jsonObject
        return createJson["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw QwenApiException("Failed to extract chat id from create response")
    }

    private suspend fun sendCompletion(
        cookies: String,
        chatId: String,
        userContent: String
    ): String {
        val msgId = UUID.randomUUID().toString()
        val childId = UUID.randomUUID().toString()

        val completionBody = CompletionRequest(
            stream = true,
            version = "2.1",
            incremental_output = true,
            chat_id = chatId,
            chat_mode = "normal",
            model = defaultModel,
            parent_id = null,
            messages = listOf(
                QwenMessage(
                    fid = msgId,
                    parentId = null,
                    childrenIds = listOf(childId),
                    role = "user",
                    content = userContent,
                    user_action = "chat",
                    files = emptyList(),
                    timestamp = System.currentTimeMillis() / 1000,
                    models = listOf(defaultModel),
                    chat_type = "t2t",
                    feature_config = defaultFeatureConfig(),
                    extra = Extra(meta = Meta(subChatType = "t2t")),
                    sub_chat_type = "t2t"
                )
            ),
            timestamp = System.currentTimeMillis() / 1000
        )

        val token = extractTokenFromCookies(cookies)

        val response: HttpResponse = httpClient.post(
            "$baseUrl/api/v2/chat/completions?chat_id=$chatId"
        ) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(HttpHeaders.Cookie, cookies)
            if (!token.isNullOrEmpty()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            header("source", "web")
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            header("x-request-id", UUID.randomUUID().toString())
            setBody(completionBody)
        }

        val rawBody = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw QwenApiException("Completion failed (${response.status.value}): $rawBody")
        }

        return parseCompletionResponse(rawBody)
    }

    private fun parseCompletionResponse(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.startsWith("{")) {
            return extractTextFromJson(json.parseToJsonElement(trimmed))
        }

        val builder = StringBuilder()
        trimmed.lineSequence().forEach { line ->
            val dataStr = when {
                line.startsWith("data:") -> line.removePrefix("data:").trim()
                else -> line.trim()
            }
            if (dataStr.isEmpty() || dataStr == "[DONE]") return@forEach
            if (!dataStr.startsWith("{")) return@forEach
            try {
                val chunkText = extractTextFromJson(json.parseToJsonElement(dataStr))
                if (chunkText.isNotEmpty()) builder.append(chunkText)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse chunk: ${dataStr.take(200)}", e)
            }
        }

        return builder.toString().ifEmpty {
            extractTextFromJson(json.parseToJsonElement(trimmed))
        }
    }

    private fun extractTextFromJson(element: JsonElement): String {
        when (element) {
            is JsonObject -> {
                element["success"]?.jsonPrimitive?.content?.let { success ->
                    if (success == "false") {
                        val details = element["data"]?.jsonObject?.get("details")?.jsonPrimitive?.content
                            ?: element["message"]?.jsonPrimitive?.content
                            ?: "Unknown API error"
                        throw QwenApiException(details)
                    }
                }

                element["content"]?.jsonPrimitive?.content?.let { if (it.isNotEmpty()) return it }

                element["choices"]?.jsonArray?.let { choices ->
                    return extractTextFromChoices(choices)
                }

                element["data"]?.let { data ->
                    val fromData = extractTextFromJson(data)
                    if (fromData.isNotEmpty()) return fromData
                }

                element["result"]?.let { result ->
                    val fromResult = extractTextFromJson(result)
                    if (fromResult.isNotEmpty()) return fromResult
                }

                element["message"]?.jsonObject?.let { message ->
                    message["content"]?.jsonPrimitive?.content?.let { if (it.isNotEmpty()) return it }
                }
            }
            is JsonArray -> {
                val combined = element.mapNotNull { item ->
                    runCatching { extractTextFromJson(item) }.getOrNull()
                }.filter { it.isNotEmpty() }.joinToString("")
                if (combined.isNotEmpty()) return combined
            }
            else -> Unit
        }
        return ""
    }

    private fun extractTextFromChoices(choices: JsonArray): String {
        val builder = StringBuilder()
        choices.forEach { choice ->
            val obj = choice.jsonObject
            val delta = obj["delta"]?.jsonObject ?: obj["message"]?.jsonObject
            delta?.get("content")?.jsonPrimitive?.content?.let { builder.append(it) }
            obj["text"]?.jsonPrimitive?.content?.let { builder.append(it) }
        }
        return builder.toString()
    }

    private fun defaultFeatureConfig() = FeatureConfig(
        thinking_enabled = true,
        output_schema = "phase",
        research_mode = "normal",
        auto_thinking = true,
        thinking_mode = "Auto",
        thinking_format = "summary",
        auto_search = true
    )

    private class QwenApiException(message: String) : Exception(message)

    companion object {
        private const val TAG = "QwenClient"
    }
}

@Serializable
data class CreateChatRequest(
    val title: String,
    val models: List<String>,
    val chat_mode: String,
    val chat_type: String,
    val timestamp: Long,
    val project_id: String
)

@Serializable
data class CompletionRequest(
    val stream: Boolean,
    val version: String,
    val incremental_output: Boolean,
    val chat_id: String,
    val chat_mode: String,
    val model: String,
    val parent_id: String?,
    val messages: List<QwenMessage>,
    val timestamp: Long
)

@Serializable
@Suppress("PropertyName")
data class QwenMessage(
    val fid: String,
    val parentId: String?,
    val childrenIds: List<String>,
    val role: String,
    val content: String,
    val user_action: String,
    val files: List<String>,
    val timestamp: Long,
    val models: List<String>,
    val chat_type: String,
    val feature_config: FeatureConfig,
    val extra: Extra,
    val sub_chat_type: String
)

@Serializable
@Suppress("PropertyName")
data class FeatureConfig(
    val thinking_enabled: Boolean,
    val output_schema: String,
    val research_mode: String,
    val auto_thinking: Boolean,
    val thinking_mode: String,
    val thinking_format: String,
    val auto_search: Boolean
)

@Serializable
data class Extra(
    val meta: Meta
)

@Serializable
data class Meta(
    val subChatType: String
)
