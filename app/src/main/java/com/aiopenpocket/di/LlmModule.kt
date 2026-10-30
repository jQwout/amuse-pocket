package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53
// @trace kartinki-urokov-cherez-muse-image-fd6f8c
// @trace video-na-vhod-v-uroke-muse-spark-08ccdb
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.LlmUsageRecorder
import com.aiopenpocket.agent.core.llm.ModelKeyGate
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenAiCompatClient
import com.aiopenpocket.agent.core.llm.ProviderConfig
import com.aiopenpocket.agent.core.llm.ProviderFallbackChain
import com.aiopenpocket.agent.core.llm.SegmentationClient
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
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
   * The Meta Model API key: ONLY what the person entered (onboarding or Settings). There is no bundled
   * key in any build, debug included (decision of Oct 30, 2026): the repository is public, and the
   * secrets plugin copies `.env` into BuildConfig, i.e. into the artifact, so a bundled key would hand
   * out the owner's Meta credits to anyone who builds the project. An empty key is not an error to hide:
   * every model action meets it with a plain "add your key" message (see [ModelKeyGate]).
   */
  private fun metaKey(settingsRepository: SettingsRepository): String =
    settingsRepository.getApiKey(META_KEY_NAME).trim()

  private const val META_KEY_NAME = "META"

  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmClient(client: OpenAiCompatClient): LlmClient = client

  /** The production transport of [OpenAiCompatClient]; in tests MockEngine takes its place. */
  @Provides
  @SingleIn(AppScope::class)
  fun provideLlmHttpEngine(): HttpClientEngine = Android.create()

  /**
   * Muse Image — for lesson images. Deliberately handed an EMPTY key for now, so the client always
   * takes its "no image" path and the lesson shows the images bundled in the build.
   *
   * TODO(meta-only follow-up): Muse Image used to go through OpenRouter, which no longer exists in this
   * project. The client's defaults already point at the Meta Model API, but the request shape has not
   * been verified against dev.meta.ai; the follow-up task checks it and replaces the empty key with
   * `metaKey(settingsRepository)`.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideImageGenClient(
    engine: HttpClientEngine,
    usageRecorder: LlmUsageRecorder,
  ): ImageGenClient = ImageGenClient(
    engine = engine,
    apiKey = { "" },
    usageRecorder = usageRecorder,
  )

  /**
   * Muse Spark watching a clip in the video lesson. Handed an EMPTY key for now, so the client always
   * returns `null` and the lesson step says the video could not be analyzed.
   *
   * TODO(meta-only follow-up): same as [provideImageGenClient] — video used to go through OpenRouter;
   * verify the Meta request shape for `video_url` and wire in `metaKey(settingsRepository)`.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideVideoUnderstandingClient(
    engine: HttpClientEngine,
    usageRecorder: LlmUsageRecorder,
  ): VideoUnderstandingClient = VideoUnderstandingClient(
    engine = engine,
    apiKey = { "" },
    usageRecorder = usageRecorder,
  )

  /**
   * SAM 3.1 outlining objects in a lesson photo, on the person's Meta key; without one the lesson step
   * says "add a Meta key" instead of sending a request that is guaranteed to fail.
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
   * The provider for the whole app: chat, the plan engine and background heartbeats. There is one, the
   * direct Meta Model API; the seam stays so consumers need not know that.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideSelectedLlmProvider(): SelectedLlmProvider = SelectedLlmProvider { LlmProvider.META }

  /**
   * "Has the person entered a Meta key?" — read on every access, so a key typed in Settings or
   * onboarding works from the very next turn, with no restart.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideModelKeyGate(settingsRepository: SettingsRepository): ModelKeyGate =
    ModelKeyGate { metaKey(settingsRepository).isNotBlank() }

  @Provides
  @SingleIn(AppScope::class)
  fun provideProviderFallbackChain(
    settingsRepository: SettingsRepository,
    modelCatalogLoader: ModelCatalogLoader,
  ): ProviderFallbackChain {
    // The Meta catalog is a static asset (see ModelCatalogLoader). OpenAiCompatClient posts to the full
    // endpoint, so /chat/completions is appended to base_url.
    fun configsFor(catalog: ModelCatalog): List<ProviderConfig> {
      val endpoint = catalog.baseUrl.trimEnd('/') + "/chat/completions"
      return catalog.models.map { entry ->
        ProviderConfig(
          name = LlmProvider.META,
          baseUrl = endpoint,
          model = entry.name,
          maxTokens = entry.maxTokens,
          reasoning = entry.reasoning,
          temperature = entry.temperature,
          topP = entry.topP,
          // The "tool-capable models only" gate is applied by ProviderFallbackChain.resolve — here we just
          // pass the fact from the catalog on to it.
          supportsTools = entry.supportsTools,
          apiKeyResolver = { metaKey(settingsRepository) },
        )
      }
    }

    // Rebuilt on every resolve: cheap (a four-line asset), and a model change in Settings is then picked
    // up from the very next turn.
    return ProviderFallbackChain(
      configsFor = { configsFor(modelCatalogLoader.loadMeta()) },
      preferredModel = { settingsRepository.getSelectedModel(LlmProvider.META.name) },
    )
  }
}
