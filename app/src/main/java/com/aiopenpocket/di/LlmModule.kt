package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53
// @trace kartinki-urokov-cherez-muse-image-fd6f8c
// @trace video-na-vhod-v-uroke-muse-spark-08ccdb
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

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
import com.aiopenpocket.agent.core.llm.SegmentationClient
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
   * The OpenRouter key: from Settings, otherwise the one baked into the debug build from .env. One
   * function for chat, images and video — otherwise Muse would one day work in the chat and silently
   * fail in a lesson.
   */
  private fun openRouterKey(settingsRepository: SettingsRepository): String =
    settingsRepository.getApiKey("OPENROUTER").ifBlank { bundledOpenRouterKey(BuildConfig.OPENROUTER_API_KEY) }

  /**
   * The Meta Model API key — ONLY from Settings: there is no bundled one in either the debug or the
   * release build (decision of Oct 26, 2026). The repository is public, and the secrets plugin copies
   * `.env` into BuildConfig, i.e. into the artifact: a bundled key would hand out Meta credits to
   * anyone who builds the project. An empty key is not an error but "go through OpenRouter" (see
   * [activeLlmProvider]).
   */
  private fun metaKey(settingsRepository: SettingsRepository): String = settingsRepository.getApiKey("META")

  private fun activeProvider(settingsRepository: SettingsRepository): LlmProvider =
    activeLlmProvider(metaKey(settingsRepository))

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  /** The production transport of [OpenAiCompatClient]; in tests MockEngine takes its place. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmHttpEngine(): HttpClientEngine = Android.create()

  /**
   * Muse Image through OpenRouter — for lesson images. The key is the same as the chat's and is read on
   * every generation: a key entered in Settings takes effect without a restart. The client treats an
   * empty key as "do not draw" — the lesson shows the images bundled in the build.
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
   * Muse Spark through OpenRouter — for video in a lesson. The key is the same as for images; video has
   * not been moved to the direct Meta Model API yet, so with only a Meta key the video lesson will not
   * answer.
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
   * SAM 3.1 outlining objects in a lesson photo. The key is the META one and ONLY that: SAM exists only
   * on the direct Meta Model API, OpenRouter does not carry it, so with an OpenRouter key alone the
   * lesson step says "add a Meta key" instead of sending a request that is guaranteed to fail.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideSegmentationClient(
    engine: HttpClientEngine,
    settingsRepository: SettingsRepository,
    usageRecorder: LlmUsageRecorder,
  ): SegmentationClient = SegmentationClient(
    engine = engine,
    apiKey = { metaKey(settingsRepository) },
    usageRecorder = usageRecorder,
  )

  /**
   * The provider for the whole app: chat, the plan engine and background heartbeats. The Meta key
   * decides it (see [activeLlmProvider]); it is read on every access, so an entered key takes effect
   * from the next turn.
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
    // The OpenRouter catalog (see ModelCatalogLoader): an asset seed that in practice is replaced by the
    // list from /api/v1/models. The Meta catalog is a static asset.
    // OpenAiCompatClient posts to the full endpoint, so /chat/completions is appended to base_url.
    fun configsFor(provider: LlmProvider, catalog: ModelCatalog, apiKey: () -> String): List<ProviderConfig> {
      val endpoint = catalog.baseUrl.trimEnd('/') + "/chat/completions"
      // Only Meta models: the app runs on Muse. On OpenRouter the filter is here, not only in parsing the
      // live catalog: a cache written by a previous version may still hold foreign models, and a model
      // chosen in Settings before an update would otherwise stay first in the chain. The direct Meta
      // Model API has no foreign models, and its ids have no "meta/" prefix — filtering by the prefix
      // would throw out the whole catalog.
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
          // The "tool-capable models only" gate is applied by ProviderFallbackChain.resolve — here we just
          // pass the fact from the catalog on to it.
          supportsTools = entry.supportsTools,
          apiKeyResolver = apiKey,
        )
      }
    }

    // The catalog is updated at runtime (OpenRouterCatalogService writes the cache), so it is rebuilt on
    // every resolve — otherwise a new list would not take effect until the process restarts.
    return ProviderFallbackChain(
      configsFor = { provider ->
        when (provider) {
          // The key from Settings takes priority; otherwise the one baked into the debug build from .env.
          LlmProvider.OPENROUTER -> configsFor(provider, modelCatalogLoader.loadOpenRouter()) {
            openRouterKey(settingsRepository)
          }
          // The key comes only from Settings — see metaKey.
          LlmProvider.META -> configsFor(provider, modelCatalogLoader.loadMeta()) {
            metaKey(settingsRepository)
          }
        }
      },
      // Each route has its own model choice (see SettingsRepository.getSelectedModel): ids on OpenRouter
      // and on Meta differ, and a shared choice would show a model in Settings the chain does not know.
      preferredModel = { settingsRepository.getSelectedModel(activeProvider(settingsRepository).name) },
    )
  }
}
