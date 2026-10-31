package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

// The instance is provided via LlmModule.provideProviderFallbackChain (the only binding).
// A provider gives a LIST of configs — the catalog's models sorted by priority. OpenAiCompatClient
// tries them in turn, falling back on an error.
class ProviderFallbackChain(
  // A function rather than a ready map: the catalog is read again on every resolve, so a list fixed when
  // the graph is built cannot go stale.
  private val configsFor: (LlmProvider) -> List<ProviderConfig>,
) {
  /** A static map — for tests and for providers whose model list does not change at runtime. */
  constructor(
    configs: Map<LlmProvider, List<ProviderConfig>>,
  ) : this({ configs[it].orEmpty() })

  /**
   * Configs of ONLY the requested provider, in attempt order: the catalog's priority order, with the
   * agent's own model family pulled to the front when it names one. There is no app-wide model choice any
   * more (it left Settings on 31 Oct 2026: the model is the agent's own knob, the app default is automatic).
   *
   * There is no cross-provider branch here any more (it used to be `AUTO`): the provider is chosen
   * explicitly, and a request cannot silently go to another vendor. Fallback between MODELS of one
   * provider remains — it changes neither the recipient of the data nor the price list.
   */
  fun resolve(requested: LlmProvider, agentModel: AgentModel? = null): List<ProviderConfig> {
    // Models without function calling are filtered HERE, not only when building configs: every way of
    // building the chain passes through this point — the live catalog, a hand-edited asset, and a
    // cache written by a previous app version. A model without tools does not fail, it silently stops
    // calling tools, and from the outside that is "the agent got dumb", not a wrong setting. For the
    // same reason models that train on the user's data are filtered here too: a previous version's
    // cache may still contain them, and they would keep receiving the person's data.
    val base = configsFor(requested).filter { it.supportsTools && !trainsOnUserData(it.model) }
    // The agent's own model goes in front. Only the single best catalog entry of the family is pulled
    // forward: "Spark" means the newest Spark the active catalog lists, not every Spark. A family the active
    // route does not have leaves the chain untouched, so a turn never fails over a model choice.
    val agentFirst = agentModel?.let { m -> base.firstOrNull { m.matches(it.model) } }
      ?: return base.take(MAX_ATTEMPTS)
    return (listOf(agentFirst) + base.filter { it !== agentFirst }).take(MAX_ATTEMPTS)
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
