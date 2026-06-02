package com.example

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class QwenApiTest {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private val baseUrl = "https://chat.qwen.ai"

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun testQwenChatFlow() = runBlocking {
        // --- 1. Create chat ---
        val createBody = CreateChatRequest(
            title = "New Chat",
            models = listOf("qwen3.6-plus"),
            chat_mode = "guest",
            chat_type = "t2t",
            timestamp = System.currentTimeMillis(),
            project_id = ""
        )

        val createResponse: HttpResponse = client.post("$baseUrl/api/v2/chats/new") {
            contentType(ContentType.Application.Json)
            setBody(createBody)
        }

        val createRaw = createResponse.bodyAsText()
        println("Create chat raw response:\n$createRaw")

        val createJson = Json.parseToJsonElement(createRaw).jsonObject
        val chatId = createJson["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: error("Failed to extract chat id from create response")
        println("Created chat id: $chatId")

        // --- 2. Send completion request ---
        // Force JSON response instead of SSE by setting Accept to application/json
        val msgId = Uuid.random().toString()
        val childId = Uuid.random().toString()

        val completionBody = CompletionRequest(
            stream = true,
            version = "2.1",
            incremental_output = true,
            chat_id = chatId,
            chat_mode = "guest",
            model = "qwen3.6-plus",
            parent_id = null,
            messages = listOf(
                Message(
                    fid = msgId,
                    parentId = null,
                    childrenIds = listOf(childId),
                    role = "user",
                    content = "кто такой кент вильямс?",
                    user_action = "chat",
                    files = emptyList(),
                    timestamp = System.currentTimeMillis() / 1000,
                    models = listOf("qwen3.6-plus"),
                    chat_type = "t2t",
                    feature_config = FeatureConfig(
                        thinking_enabled = true,
                        output_schema = "phase",
                        research_mode = "normal",
                        auto_thinking = true,
                        thinking_mode = "Auto",
                        thinking_format = "summary",
                        auto_search = true
                    ),
                    extra = Extra(meta = Meta(subChatType = "t2t")),
                    sub_chat_type = "t2t"
                )
            ),
            timestamp = System.currentTimeMillis() / 1000
        )

        val completionResponse: HttpResponse = client.post(
            "$baseUrl/api/v2/chat/completions?chat_id=$chatId"
        ) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json) // <-- key: ask for JSON instead of event-stream
            setBody(completionBody)
        }

        val rawBody = completionResponse.bodyAsText()
        println("Status: ${completionResponse.status}")
        println("Response body:\n$rawBody")
    }
}

// --- DTOs ---

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
data class CreateChatResponse(
    val success: Boolean,
    val request_id: String,
    val data: ChatData
)

@Serializable
data class ChatData(
    val id: String
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
    val messages: List<Message>,
    val timestamp: Long
)

@Serializable
@Suppress("PropertyName")
data class Message(
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
