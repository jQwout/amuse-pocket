package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

enum class LlmProvider {
  AUTO, NIM, GROQ, GITHUB
}

data class ProviderConfig(
  val name: LlmProvider,
  val baseUrl: String,
  val model: String,
  // Дефолт для провайдеров без своего значения (GROQ/GITHUB). NIM-модели задают max_tokens
  // в assets/config/models.json. Это лимит ДЛИНЫ ОТВЕТА (output), препромпт сюда не входит.
  val maxTokens: Int = 4096,
  val reasoning: Boolean = false,
  val apiKeyResolver: () -> String,
)
