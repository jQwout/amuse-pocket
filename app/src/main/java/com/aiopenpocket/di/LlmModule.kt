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
import com.aiopenpocket.agent.core.llm.bundledOpenRouterKey
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.agent.core.llm.activeLlmProvider
import com.aiopenpocket.agent.core.llm.isMetaModel
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

  /**
   * Ключ Meta Model API — ТОЛЬКО из Настроек: вшитого нет ни в отладочной, ни в релизной сборке
   * (решение 26 окт 2026). Репозиторий открыт, а секретный плагин копирует `.env` в BuildConfig, то
   * есть внутрь артефакта: вшитый ключ раздавал бы кредиты Meta всем, кто соберёт проект. Пустой
   * ключ — не ошибка, а «идём через OpenRouter» (см. [activeLlmProvider]).
   */
  private fun metaKey(settingsRepository: SettingsRepository): String = settingsRepository.getApiKey("META")

  private fun activeProvider(settingsRepository: SettingsRepository): LlmProvider =
    activeLlmProvider(metaKey(settingsRepository))

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  /** Прод-транспорт [OpenAiCompatClient]; в тестах его место занимает MockEngine. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmHttpEngine(): HttpClientEngine = Android.create()

  /**
   * Muse Image через OpenRouter — для картинок уроков. Ключ тот же, что у чата, и читается при
   * каждой генерации: ключ, введённый в Настройках, действует без перезапуска. Пустой ключ клиент
   * понимает как «не рисовать» — урок показывает картинки из сборки.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideImageGenClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    usageRecorder: LlmUsageRecorder,
  ): ImageGenClient = ImageGenClient(
    engine = engine,
    apiKey = { openRouterKey(settingsRepository) },
    usageRecorder = usageRecorder,
  )

  /**
   * Muse Spark через OpenRouter — для видео в уроке. Ключ тот же, что у картинок; на прямой Meta
   * Model API видео пока не переведено, так что с одним ключом Meta урок о видео не ответит.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideVideoUnderstandingClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    usageRecorder: LlmUsageRecorder,
  ): VideoUnderstandingClient = VideoUnderstandingClient(
    engine = engine,
    apiKey = { openRouterKey(settingsRepository) },
    usageRecorder = usageRecorder,
  )

  /**
   * Провайдер на всё приложение: чат, план-движок и фоновые heartbeat'ы. Решает ключ Meta (см.
   * [activeLlmProvider]); читается на каждом обращении, так что введённый ключ действует со
   * следующего хода.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideSelectedLlmProvider(settingsRepository: SettingsRepository): SelectedLlmProvider =
    SelectedLlmProvider { activeProvider(settingsRepository) }

  @Provides
  @SingleIn(AppScope::class)
  fun provideProviderFallbackChain(
    settingsRepository: SettingsRepository,
    modelCatalogLoader: ModelCatalogLoader,
  ): ProviderFallbackChain {
    // Каталог OpenRouter (см. ModelCatalogLoader): сид из ассетов, который в бою заменяется списком
    // с /api/v1/models. Каталог Meta — статичный ассет.
    // OpenAiCompatClient постит на полный endpoint, поэтому дописываем /chat/completions к base_url.
    fun configsFor(provider: LlmProvider, catalog: ModelCatalog, apiKey: () -> String): List<ProviderConfig> {
      val endpoint = catalog.baseUrl.trimEnd('/') + "/chat/completions"
      // Только модели Meta: приложение работает на Muse. На OpenRouter отсев здесь, а не только в
      // разборе живого каталога: кеш, записанный прошлой версией, ещё может держать чужие модели, и
      // модель, выбранная в Настройках до обновления, иначе осталась бы в цепочке первой. У прямого
      // Meta Model API чужих моделей нет, а id там без префикса «meta/» — отсев по префиксу выбросил
      // бы весь каталог.
      val models = if (provider == LlmProvider.OPENROUTER) catalog.models.filter { isMetaModel(it.name) } else catalog.models
      return models.map { entry ->
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

    // Каталог обновляется в рантайме (OpenRouterCatalogService пишет кеш), поэтому он пересобирается
    // на каждый resolve — иначе новый список не действовал бы до перезапуска процесса.
    return ProviderFallbackChain(
      configsFor = { provider ->
        when (provider) {
          // Ключ из Настроек в приоритете; иначе — вшитый в отладочную сборку из .env.
          LlmProvider.OPENROUTER -> configsFor(provider, modelCatalogLoader.loadOpenRouter()) {
            openRouterKey(settingsRepository)
          }
          // Ключ только из Настроек — см. metaKey.
          LlmProvider.META -> configsFor(provider, modelCatalogLoader.loadMeta()) {
            metaKey(settingsRepository)
          }
        }
      },
      // Выбор модели у каждого маршрута свой (см. SettingsRepository.getSelectedModel): id у OpenRouter
      // и у Meta разные, и общий выбор показывал бы в Настройках модель, которой цепочка не знает.
      preferredModel = { settingsRepository.getSelectedModel(activeProvider(settingsRepository).name) },
    )
  }
}
