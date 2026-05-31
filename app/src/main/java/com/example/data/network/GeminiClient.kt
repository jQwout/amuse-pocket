package com.example.data.network

import android.util.Log
import com.example.BuildConfig
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

@Serializable
data class GeminiPart(
    val text: String? = null
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

class GeminiClient {

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
        history: List<GeminiContent>,
        systemPrompt: String = "You are a friendly and polite LLM Chat assistant inside Queen Chat. Address the user nicely."
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w("GeminiClient", "API Key is missing or default. Returning dummy reply.")
            return@withContext "Привет! Приложение успешно настроено. Чтобы отправлять реальные запросы к LLM (Gemini), вам необходимо указать рабочий GEMINI_API_KEY в панели Secrets в AI Studio. \n\nКак только ключ будет добавлен, Ktor-клиент автоматически начнет передавать ему ваши сообщения!"
        }

        val requestUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val requestPayload = GeminiRequest(
            contents = history,
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
        )

        try {
            val response: HttpResponse = httpClient.post(requestUrl) {
                setBody(requestPayload)
                contentType(io.ktor.http.ContentType.Application.Json)
            }

            if (response.status.value in 200..299) {
                val body: GeminiResponse = Json.decodeFromString(response.bodyAsText())
                val text = body.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                text ?: "Пустой ответ от LLM."
            } else {
                "ошибка запроса к LLM: Код ${response.status.value}. Лог: ${response.bodyAsText()}"
            }
        } catch (e: Exception) {
            "Ошибка: ${e.localizedMessage ?: "Сетевой сбой при общении с ИИ."}"
        }
    }
}
