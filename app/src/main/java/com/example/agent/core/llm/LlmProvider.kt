package com.aiopenpocket.agent.core.llm

enum class LlmProvider {
  AUTO, NIM, GROQ, GITHUB
}

data class ProviderConfig(
  val name: LlmProvider,
  val baseUrl: String,
  val model: String,
  val apiKeyResolver: () -> String,
)
