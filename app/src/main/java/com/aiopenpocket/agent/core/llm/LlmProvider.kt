package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Who requests to the model go through. The models are the same on both routes — Meta's Muse; only
 * the path differs:
 * - [META] — the direct Meta Model API (`api.meta.ai`). The primary route: the hackathon's first
 *   screening accepts "Meta Model API and/or Meta's open weights", and OpenRouter is literally neither;
 *   and only here are SAM 3.1 and Voice Transcribe available.
 * - [OPENROUTER] — the backup path to the same models; from its catalog the app takes only Meta
 *   models (see [isMetaModel]).
 *
 * Which one is in effect is decided by the key (see [activeLlmProvider]). It used to be
 * `NIM, OPENROUTER`, and before that `AUTO, NIM, GROQ, GITHUB`; NVIDIA NIM was removed on Oct 25,
 * 2026 — another vendor meant other models in a course about Meta. There is no cross-provider
 * auto-fallback and there will not be: fallback happens between MODELS of one provider (see
 * [ProviderFallbackChain]), otherwise a request could silently go to another recipient.
 */
enum class LlmProvider {
  OPENROUTER,
  META,
}

/**
 * The key decides the provider: with a Meta Model API key entered, requests go straight to Meta;
 * without one — through OpenRouter.
 *
 * There is no separate switch on purpose. The Meta key is entered by the user (there is no bundled one,
 * see LlmModule), and "entered a key — it works directly" needs no explanation of what a route is.
 * Without a Meta key the debug build keeps working on the bundled OpenRouter key. ONE function for both
 * the chain and the "where requests go" line in Settings: if they diverged, the screen would promise
 * Meta while the turn went to OpenRouter.
 */
fun activeLlmProvider(metaKey: String): LlmProvider =
  if (metaKey.isNotBlank()) LlmProvider.META else LlmProvider.OPENROUTER

/**
 * The OpenRouter key baked into the debug build from `.env`, or an empty string. Without it the app on
 * Muse would not make it to Settings — onboarding calls the model itself. ONE function for both the
 * chain and the warning in Settings: if they diverged, the placeholder would go into a request as a
 * real key, and there would be no warning. A live key cannot get into a store build — a check in
 * app/build.gradle.kts stops it.
 */
fun bundledOpenRouterKey(buildKey: String): String =
  buildKey.takeUnless { it.isBlank() || it == OPENROUTER_KEY_PLACEHOLDER }.orEmpty()

const val OPENROUTER_KEY_PLACEHOLDER = "MY_OPENROUTER_API_KEY"

/**
 * The provider a request goes to — read on every access, not fixed when the graph is built: a Meta key
 * entered in Settings moves the very next turn to the direct Meta Model API — the chat, the plan
 * engine and background heartbeats alike, without a restart.
 *
 * A separate type rather than `SettingsRepository` directly: consumers (the agent loop, heartbeats)
 * need exactly one fact, "where to send", and pulling the key store in for it would mean pulling
 * EncryptedSharedPreferences with the Android Keystore into tests.
 */
fun interface SelectedLlmProvider {
  operator fun invoke(): LlmProvider
}

data class ProviderConfig(
  val name: LlmProvider,
  val baseUrl: String,
  val model: String,
  // The default for models that did not set their own value. The catalogs (assets/config/models_*.json)
  // set max_tokens themselves. This is the limit on the ANSWER LENGTH (output); the prompt is not part of it.
  val maxTokens: Int = 4096,
  val reasoning: Boolean = false,
  val temperature: Double? = null,
  val topP: Double? = null,
  /**
   * Whether the model can call tools (see [ModelEntry.supportsTools]). The gate is applied by
   * [ProviderFallbackChain.resolve] — one point for every way to build a chain.
   */
  val supportsTools: Boolean = true,
  val apiKeyResolver: () -> String,
)
