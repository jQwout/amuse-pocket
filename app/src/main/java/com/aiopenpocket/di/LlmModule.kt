package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.llmProviderOf
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

  /** Выбранный вендор — общий на всё приложение: чат, план-движок и фоновые heartbeat'ы. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideSelectedLlmProvider(settingsRepository: SettingsRepository): SelectedLlmProvider =
    SelectedLlmProvider { llmProviderOf(settingsRepository.getSelectedProvider()) }

  @Provides
  @SingleIn(AppScope::class)
  fun provideProviderFallbackChain(
    settingsRepository: SettingsRepository,
    modelCatalogLoader: ModelCatalogLoader,
  ): ProviderFallbackChain {
    // Каталог на провайдера (см. ModelCatalogLoader): у NIM статичный ассет, у OpenRouter — сид,
    // который в бою заменяется списком с /api/v1/models.
    // OpenAiCompatClient постит на полный endpoint, поэтому дописываем /chat/completions к base_url.
    fun configsFor(provider: LlmProvider, catalog: ModelCatalog, apiKey: () -> String): List<ProviderConfig> {
      val endpoint = catalog.baseUrl.trimEnd('/') + "/chat/completions"
      // Модели без function calling отсеиваем здесь же, а не только в селекторе Настроек: иначе
      // такая модель осталась бы в фолбэк-цепочке и агент молча «отупел» бы на ней после ошибки
      // предыдущей (см. ModelEntry.supportsTools).
      return catalog.models.filter { it.supportsTools }.map { entry ->
        ProviderConfig(
          name = provider,
          baseUrl = endpoint,
          model = entry.name,
          maxTokens = entry.maxTokens,
          reasoning = entry.reasoning,
          temperature = entry.temperature,
          topP = entry.topP,
          apiKeyResolver = apiKey,
        )
      }
    }

    // NIM считаем один раз: его каталог — статичный ассет. У OpenRouter каталог обновляется в
    // рантайме (OpenRouterCatalogService пишет кеш), поэтому он пересобирается на каждый resolve —
    // иначе новый список не действовал бы до перезапуска процесса.
    val nimConfigs = configsFor(LlmProvider.NIM, modelCatalogLoader.loadNim()) {
      // Ручной ключ из Настроек в приоритете; иначе — ключ, вшитый в сборку из .env (BuildConfig).
      settingsRepository.getApiKey("NVIDIA").ifBlank {
        BuildConfig.NVIDIA_API_KEY.takeUnless { it == "MY_NVIDIA_API_KEY" }.orEmpty()
      }
    }
    return ProviderFallbackChain(
      configsFor = { provider ->
        when (provider) {
          LlmProvider.NIM -> nimConfigs
          // У OpenRouter вшитого в сборку ключа нет — только пользовательский из Настроек. Пустой
          // ключ это нормальное состояние до того, как пользователь его введёт.
          LlmProvider.OPENROUTER -> configsFor(provider, modelCatalogLoader.loadOpenRouter()) {
            settingsRepository.getApiKey("OPENROUTER")
          }
        }
      },
      preferredModel = { settingsRepository.getSelectedModel() },
    )
  }
}
