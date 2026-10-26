package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

// The instance is provided via LlmModule.provideProviderFallbackChain (the only binding).
// A provider gives a LIST of configs — the catalog's models sorted by priority. OpenAiCompatClient
// tries them in turn, falling back on an error.
class ProviderFallbackChain(
  // A function rather than a ready map: the OpenRouter catalog is updated at runtime
  // (OpenRouterCatalogService), and a list fixed when the graph is built would live until the process
  // restarts.
  private val configsFor: (LlmProvider) -> List<ProviderConfig>,
  // A lambda (not a value) — like apiKeyResolver in ProviderConfig: it is read again on every resolve(),
  // so a model change in Settings is picked up from the very next turn, without rebuilding the DI graph.
  private val preferredModel: () -> String? = { null },
) {
  /** A static map — for tests and for providers whose model list does not change at runtime. */
  constructor(
    configs: Map<LlmProvider, List<ProviderConfig>>,
    preferredModel: () -> String? = { null },
  ) : this({ configs[it].orEmpty() }, preferredModel)

  /**
   * Configs of ONLY the requested provider, in attempt order: the model the user chose first, the rest
   * as a fallback by priority.
   *
   * There is no cross-provider branch here any more (it used to be `AUTO`): the provider is chosen
   * explicitly, and a request cannot silently go to another vendor. Fallback between MODELS of one
   * provider remains — it changes neither the recipient of the data nor the price list.
   */
  fun resolve(requested: LlmProvider): List<ProviderConfig> {
    // Models without function calling are filtered HERE, not only when building configs: every way of
    // building the chain passes through this point — the live catalog, a hand-edited asset, and a
    // cache written by a previous app version. A model without tools does not fail, it silently stops
    // calling tools, and from the outside that is "the agent got dumb", not a wrong setting. For the
    // same reason models that train on the user's data are filtered here too: a previous version's
    // cache may still contain them, and a user who chose one would keep sending their data into it.
    // (Non-Meta models are filtered when building configs in LlmModule: that is app policy, not a
    // property of the chain.)
    val base = configsFor(requested).filter { it.supportsTools && !trainsOnUserData(it.model) }
    // "AUTO" is a settings sentinel, "not chosen by hand, go by priority", not a model name. The
    // LlmProvider.AUTO mode is gone, but the string lives on in SettingsRepository as the default, and
    // without an explicit check it would be silently looked up among the models (it would find none —
    // harmless, but by accident).
    val preferred = preferredModel()?.takeIf { it.isNotBlank() && it != "AUTO" }
      ?: return base.take(MAX_ATTEMPTS)
    // Compare ONLY by model name: base is already narrowed to one provider. Truncate AFTER the choice:
    // the chosen model may be the last one in the catalog.
    val (match, rest) = base.partition { it.model == preferred }
    return (match + rest).take(MAX_ATTEMPTS)
  }

  private companion object {
    /**
     * The maximum number of models tried in one turn.
     *
     * Fallback cures ONE class of problems: a particular model is rate-limited or down right now.
     * After two or three refusals in a row the problem is not the model but the key, the network or
     * the provider itself — and trying further means heating the phone and burning the user's minutes:
     * without a ceiling one wrong key would mean a 401 on every model of the catalog in turn.
     */
    const val MAX_ATTEMPTS = 5
  }
}
