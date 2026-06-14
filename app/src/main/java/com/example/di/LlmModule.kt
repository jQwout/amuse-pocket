package com.aiopenpocket.di

import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.data.repository.SettingsRepository
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object LlmModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  @Provides
  @SingleIn(AppScope::class)
  fun provideProviderFallbackChain(
    settingsRepository: SettingsRepository,
  ): ProviderFallbackChain {
    val configs = mapOf(
      LlmProvider.NIM to ProviderConfig(
        name = LlmProvider.NIM,
        baseUrl = "https://integrate.api.nvidia.com/v1/chat/completions",
        model = "meta/llama-3.1-70b-instruct",
        apiKeyResolver = {
          settingsRepository.getApiKey("NVIDIA")
        },
      ),
      LlmProvider.GROQ to ProviderConfig(
        name = LlmProvider.GROQ,
        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
        model = "llama-3.1-70b-versatile",
        apiKeyResolver = {
          settingsRepository.getApiKey("GROQ")
        },
      ),
      LlmProvider.GITHUB to ProviderConfig(
        name = LlmProvider.GITHUB,
        baseUrl = "https://models.inference.ai.azure.com/chat/completions",
        model = "meta-llama-3.1-70b-instruct",
        apiKeyResolver = {
          settingsRepository.getApiKey("GITHUB")
        },
      ),
    )
    return ProviderFallbackChain(configs)
  }
}
