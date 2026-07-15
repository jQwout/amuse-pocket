package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1

import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.data.repository.SettingsRepository
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android

@ContributesTo(AppScope::class)
@BindingContainer
object LlmModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  /** Прод-транспорт [OpenAiCompatClient]; в тестах его место занимает MockEngine. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmHttpEngine(): HttpClientEngine = Android.create()

  @Provides
  @SingleIn(AppScope::class)
  fun provideProviderFallbackChain(
    settingsRepository: SettingsRepository,
    modelCatalogLoader: ModelCatalogLoader,
  ): ProviderFallbackChain {
    // NIM-модели берутся из assets/config/models.json (отсортированы по priority).
    // OpenAiCompatClient постит на полный endpoint, поэтому дописываем /chat/completions к base_url.
    val catalog = modelCatalogLoader.load()
    val nimEndpoint = catalog.baseUrl.trimEnd('/') + "/chat/completions"
    val nimConfigs = catalog.models.map { entry ->
      ProviderConfig(
        name = LlmProvider.NIM,
        baseUrl = nimEndpoint,
        model = entry.name,
        maxTokens = entry.maxTokens,
        reasoning = entry.reasoning,
        temperature = entry.temperature,
        topP = entry.topP,
        // Ручной ключ из Настроек в приоритете; иначе — ключ, вшитый в сборку из .env (BuildConfig).
        apiKeyResolver = {
          settingsRepository.getApiKey("NVIDIA").ifBlank {
            BuildConfig.NVIDIA_API_KEY.takeUnless { it == "MY_NVIDIA_API_KEY" }.orEmpty()
          }
        },
      )
    }

    val configs = mapOf(
      LlmProvider.NIM to nimConfigs,
      LlmProvider.GROQ to listOf(
        ProviderConfig(
          name = LlmProvider.GROQ,
          baseUrl = "https://api.groq.com/openai/v1/chat/completions",
          model = "llama-3.1-70b-versatile",
          apiKeyResolver = {
            settingsRepository.getApiKey("GROQ")
          },
        ),
      ),
      LlmProvider.GITHUB to listOf(
        ProviderConfig(
          name = LlmProvider.GITHUB,
          baseUrl = "https://models.inference.ai.azure.com/chat/completions",
          model = "meta-llama-3.1-70b-instruct",
          apiKeyResolver = {
            settingsRepository.getApiKey("GITHUB")
          },
        ),
      ),
    )
    return ProviderFallbackChain(
      configs,
      preferredNimModel = { settingsRepository.getSelectedModel() },
    )
  }
}
