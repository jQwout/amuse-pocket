package com.aiopenpocket.data.network

// @trace streaming-llm-chat-6595a1

import android.util.Log
import com.aiopenpocket.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@Serializable
data class NvidiaMessage(
    val role: String,
    val content: String
)

@Serializable
data class NvidiaRequest(
    val model: String,
    val messages: List<NvidiaMessage>,
    val temperature: Double = 0.7,
    val top_p: Double = 0.8,
    val max_tokens: Int = 1024,
    val stream: Boolean = false
)

@Serializable
data class NvidiaChoice(
    val index: Int? = null,
    val message: NvidiaMessage? = null
)

@Serializable
data class NvidiaResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<NvidiaChoice>? = null
)

@Inject
@SingleIn(AppScope::class)
class NvidiaClient {

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = true
            })
        }
    }

    suspend fun generateResponse(
        history: List<NvidiaMessage>,
        userCustomApiKey: String? = null,
        systemPrompt: String = "You are a friendly and polite AI companion. Address the user nicely."
    ): String = withContext(Dispatchers.IO) {
        // Attempt to find NVIDIA_API_KEY first, if not declared, fallback to GEMINI_API_KEY
        // This is extremely graceful and accepts keys provided by the user in any field.
        var apiKey = userCustomApiKey ?: ""
        if (apiKey.trim().isEmpty()) {
            try {
                // Using reflection or safe BuildConfig check
                apiKey = BuildConfig.NVIDIA_API_KEY
            } catch (e: Exception) {
                Log.d("NvidiaClient", "NVIDIA_API_KEY not defined in BuildConfig: ${e.message}")
            }

            if (apiKey.isEmpty() || apiKey == "MY_NVIDIA_API_KEY") {
                apiKey = BuildConfig.GEMINI_API_KEY
            }
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "MY_NVIDIA_API_KEY") {
            Log.w("NvidiaClient", "No API Key configured. Returning template message.")
            return@withContext "Привет! Приложение AI Open Pocket переведено на протокол NVIDIA NIM (инвидиб).\n\n" +
                    "Чтобы отправлять реальные запросы к LLM (Llama), укажите рабочий NVIDIA_API_KEY (или GEMINI_API_KEY) " +
                    "в панели Secrets в AI Studio, либо введите его вручную в настройках в боковом меню!\n\n" +
                    "После добавления ключа Ktor-клиент автоматически начнет передавать сообщения к https://integrate.api.nvidia.com!"
        }

        // Base URL and model from jQwout/ai-editor configuration
        val requestUrl = "https://integrate.api.nvidia.com/v1/chat/completions"
        val requestModel = "meta/llama-3.1-70b-instruct"

        // Build list of messages incorporating the system prompt at the beginning
        val fullMessages = mutableListOf<NvidiaMessage>()
        fullMessages.add(NvidiaMessage(role = "system", content = systemPrompt))
        fullMessages.addAll(history)

        val requestPayload = NvidiaRequest(
            model = requestModel,
            messages = fullMessages
        )

        try {
            val response: HttpResponse = httpClient.post(requestUrl) {
                header("Authorization", "Bearer $apiKey")
                setBody(requestPayload)
                contentType(io.ktor.http.ContentType.Application.Json)
            }

            if (response.status.value in 200..299) {
                val responseText = response.bodyAsText()
                val body: NvidiaResponse = Json.decodeFromString(responseText)
                val text = body.choices?.firstOrNull()?.message?.content
                text ?: "Пустой ответ от LLM."
            } else {
                "Ошибка NVIDIA NIM API: Код ${response.status.value}. Лог: ${response.bodyAsText()}"
            }
        } catch (e: Exception) {
            "Ошибка отправки: ${e.localizedMessage ?: "Сетевой сбой при общении с NVIDIA NIM."}"
        }
    }
}
