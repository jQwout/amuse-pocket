package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53
// @trace kartinki-urokov-cherez-muse-image-fd6f8c
// @trace video-na-vhod-v-uroke-muse-spark-08ccdb

import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.LlmUsageRecorder
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.bundledNvidiaKey
import com.aiopenpocket.agent.core.llm.bundledOpenRouterKey
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
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

  /**
   * Ключ OpenRouter: из Настроек, иначе вшитый в отладочную сборку из .env. Одна функция на чат,
   * картинки и видео — иначе Muse однажды заработала бы в чате и молча отказала в уроке.
   */
  private fun openRouterKey(settingsRepository: SettingsRepository): String =
    settingsRepository.getApiKey("OPENROUTER").ifBlank { bundledOpenRouterKey(BuildConfig.OPENROUTER_API_KEY) }

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  /** Прод-транспорт [OpenAiCompatClient]; в тестах его место занимает MockEngine. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmHttpEngine(): HttpClientEngine = Android.create()

  /**
   * Muse Image через OpenRouter — для картинок уроков. Ключ тот же, что у чата на OpenRouter, и
   * читается при каждой генерации: ключ, введённый в Настройках, действует без перезапуска.
   *
   * Только когда OpenRouter выбран провайдером. Иначе урок молча слал бы запросы вендору, которого
   * пользователь не выбирал, и по его прайсу — ровно то, от чего избавились, убрав AUTO. Пустой ключ
   * клиент понимает как «не рисовать», и урок на NIM показывает картинки из сборки.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideImageGenClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    selectedProvider: SelectedLlmProvider,
    usageRecorder: LlmUsageRecorder,
  ): ImageGenClient = ImageGenClient(
    engine = engine,
    apiKey = {
      if (selectedProvider() == LlmProvider.OPENROUTER) openRouterKey(settingsRepository) else ""
    },
    usageRecorder = usageRecorder,
  )

  /**
   * Muse Spark через OpenRouter — для видео в уроке. Тот же гейт, что у картинок: видео уходит
   * провайдеру целиком, со всем, что попало в кадр, и только тому вендору, которого выбрал пользователь.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideVideoUnderstandingClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    selectedProvider: SelectedLlmProvider,
    usageRecorder: LlmUsageRecorder,
  ): VideoUnderstandingClient = VideoUnderstandingClient(
    engine = engine,
    apiKey = {
      if (selectedProvider() == LlmProvider.OPENROUTER) openRouterKey(settingsRepository) else ""
    },
    usageRecorder = usageRecorder,
  )

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
      return catalog.models.map { entry ->
        ProviderConfig(
          name = provider,
          baseUrl = endpoint,
          model = entry.name,
          maxTokens = entry.maxTokens,
          reasoning = entry.reasoning,
          temperature = entry.temperature,
          topP = entry.topP,
          // Гейт «только модели с tools» применяет ProviderFallbackChain.resolve — здесь мы лишь
          // доносим до него факт из каталога.
          supportsTools = entry.supportsTools,
          apiKeyResolver = apiKey,
        )
      }
    }

    // NIM считаем один раз: его каталог — статичный ассет. У OpenRouter каталог обновляется в
    // рантайме (OpenRouterCatalogService пишет кеш), поэтому он пересобирается на каждый resolve —
    // иначе новый список не действовал бы до перезапуска процесса.
    val nimConfigs = configsFor(LlmProvider.NIM, modelCatalogLoader.loadNim()) {
      // Ручной ключ из Настроек в приоритете; иначе — ключ, вшитый в сборку из .env (BuildConfig).
      settingsRepository.getApiKey("NVIDIA").ifBlank { bundledNvidiaKey(BuildConfig.NVIDIA_API_KEY) }
    }
    return ProviderFallbackChain(
      configsFor = { provider ->
        when (provider) {
          LlmProvider.NIM -> nimConfigs
          // Ключ из Настроек в приоритете; иначе — вшитый в отладочную сборку из .env.
          LlmProvider.OPENROUTER -> configsFor(provider, modelCatalogLoader.loadOpenRouter()) {
            openRouterKey(settingsRepository)
          }
        }
      },
      preferredModel = { settingsRepository.getSelectedModel() },
    )
  }
}
