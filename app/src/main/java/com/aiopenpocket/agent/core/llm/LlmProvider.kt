package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

/**
 * Who requests to the model go through: the direct Meta Model API (`api.meta.ai`), and nothing else.
 *
 * The hackathon's first screening accepts "Meta Model API and/or Meta's open weights", and the owner
 * removed OpenRouter from the project on Oct 30, 2026: the app talks to Meta with a key the PERSON
 * enters (onboarding or Settings), and no key is bundled into any build, debug included. Earlier
 * revisions of this enum were `NIM, OPENROUTER`, before that `AUTO, NIM, GROQ, GITHUB`, then
 * `OPENROUTER, META`.
 *
 * The enum stays with a single value rather than being deleted: the provider is part of
 * [ProviderConfig], of the spend records and of the per-route model choice, and a second route (a
 * server of our own, say) would slot in here again. There is no cross-provider auto-fallback and
 * there will not be: fallback happens between MODELS of one provider (see [ProviderFallbackChain]),
 * otherwise a request could silently go to another recipient.
 */
enum class LlmProvider {
  META,
}

/**
 * The provider a request goes to — read on every access, not fixed when the graph is built.
 *
 * Kept as a seam although there is a single provider today: consumers (the agent loop, heartbeats)
 * need exactly one fact, "where to send", and pulling the key store in for it would mean pulling
 * EncryptedSharedPreferences with the Android Keystore into tests.
 */
fun interface SelectedLlmProvider {
  operator fun invoke(): LlmProvider
}

/**
 * Whether the person has entered a Meta Model API key. Read on every access: a key entered in
 * Settings or onboarding works from the very next turn.
 *
 * This is the "no key" gate. No key is bundled into any build, so a fresh install has none, and every
 * model action must meet that with a plain "add your key" message instead of an HTTP 401 from Meta.
 * The gate is applied at the points where a model call STARTS (a chat turn in `TurnManager`, a
 * background wakeup in `HeartbeatRunner`); the HTTP client has its own last-resort check
 * (`blank_key`) for everything that reaches it without passing through those.
 */
fun interface ModelKeyGate {
  operator fun invoke(): Boolean
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
