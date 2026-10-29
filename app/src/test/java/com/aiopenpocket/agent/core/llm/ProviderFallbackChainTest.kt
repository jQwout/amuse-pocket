package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackChainTest {

  private fun config(model: String, supportsTools: Boolean = true) = ProviderConfig(
    name = LlmProvider.OPENROUTER,
    baseUrl = "https://example/openrouter",
    model = model,
    supportsTools = supportsTools,
    apiKeyResolver = { "key" },
  )

  private fun chainOf(vararg configs: ProviderConfig, preferred: String? = null) = ProviderFallbackChain(
    configs = mapOf(LlmProvider.OPENROUTER to configs.toList()),
    preferredModel = { preferred },
  )

  @Test
  fun `preserves model order by priority when nothing is preferred`() {
    val chain = chainOf(config("model-1"), config("model-2"), config("model-3"))
    assertEquals(listOf("model-1", "model-2", "model-3"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * The model choice in Settings takes effect: the chosen one goes first, the rest as fallback. The old
   * check `it.name == LlmProvider.NIM` once silently sent an OpenRouter user back to the default model,
   * although Settings showed them the chosen one.
   */
  @Test
  fun `preferred model goes first, the rest stay as fallbacks`() {
    val chain = chainOf(config("meta/muse-spark-1.3"), config("meta/muse-spark-1.2"), config("meta/muse-wonder"), preferred = "meta/muse-wonder")
    assertEquals(
      listOf("meta/muse-wonder", "meta/muse-spark-1.3", "meta/muse-spark-1.2"),
      chain.resolve(LlmProvider.OPENROUTER).map { it.model },
    )
  }

  /** "AUTO" is the settings sentinel for "not chosen by hand", not a model name: the order stays by priority. */
  @Test
  fun `AUTO sentinel is not treated as a model name`() {
    val chain = chainOf(config("model-1"), config("model-2"), preferred = "AUTO")
    assertEquals(listOf("model-1", "model-2"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /** A model missing from the catalog (withdrawn or left over from a previous version) does not break the order. */
  @Test
  fun `preferred model missing from the catalog is ignored`() {
    val chain = chainOf(config("model-1"), config("model-2"), preferred = "anthropic/claude-sonnet-5")
    assertEquals(listOf("model-1", "model-2"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * Without a ceiling one wrong key would mean an attempt on every catalog model in a row on every turn:
   * the phone heats up, the user waits, and the answer is still "did not work".
   */
  @Test
  fun `the attempt chain is capped`() {
    val huge = chainOf(*(1..300).map { config("vendor/model-$it") }.toTypedArray())
    assertEquals(5, huge.resolve(LlmProvider.OPENROUTER).size)
  }

  /** The ceiling must not cut off the CHOSEN model, even if it is the two-hundredth in the catalog. */
  @Test
  fun `a preferred model beyond the cap still goes first`() {
    val huge = chainOf(*(1..300).map { config("vendor/model-$it") }.toTypedArray(), preferred = "vendor/model-200")
    val resolved = huge.resolve(LlmProvider.OPENROUTER).map { it.model }
    assertEquals("vendor/model-200", resolved.first())
    assertEquals(5, resolved.size)
  }

  /**
   * The "function-calling models only" gate stands here, not only in config assembly: every way of building
   * the chain goes through resolve — the live catalog, a hand-edited asset and a cache written by a previous
   * version of the app.
   */
  @Test
  fun `models without tool support never enter the chain`() {
    val chain = chainOf(config("no-tools", supportsTools = false), config("with-tools"))
    assertEquals(listOf("with-tools"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /** Even if the user chose such a model earlier (the catalog has changed since). */
  @Test
  fun `a preferred model without tool support is not resurrected`() {
    val chain = chainOf(config("no-tools", supportsTools = false), config("with-tools"), preferred = "no-tools")
    assertEquals(listOf("with-tools"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * A cache written by a version without the filter may still hold a contributor model, and the user may
   * have it chosen. It does not get into the chain either way.
   */
  @Test
  fun `a model that trains on user data never enters the chain, even when preferred`() {
    val chain = chainOf(
      config("meta/muse-spark-1.3-contributor"),
      config("meta/muse-spark-1.3"),
      preferred = "meta/muse-spark-1.3-contributor",
    )
    assertEquals(listOf("meta/muse-spark-1.3"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  @Test
  fun `empty catalog resolves to empty list`() {
    assertTrue(chainOf().resolve(LlmProvider.OPENROUTER).isEmpty())
  }

  // --- the agent's own model (AgentDefinition.model) -------------------------------------------

  private fun metaConfig(model: String) = config(model).copy(name = LlmProvider.META)

  private fun twoRoutes(preferred: String? = null) = ProviderFallbackChain(
    configs = mapOf(
      // The direct route: ids without a vendor prefix, catalog already sorted by priority.
      LlmProvider.META to listOf("muse-spark-1.3", "muse-spark-1.2", "muse-wonder-1.0").map(::metaConfig),
      LlmProvider.OPENROUTER to listOf("meta/muse-spark-1.3", "meta/muse-wonder", "meta/muse-spark-1.2").map { config(it) },
    ),
    preferredModel = { preferred },
  )

  @Test
  fun `agent model WONDER goes first on both routes, despite the id spelling differing`() {
    val chain = twoRoutes()
    assertEquals(
      listOf("muse-wonder-1.0", "muse-spark-1.3", "muse-spark-1.2"),
      chain.resolve(LlmProvider.META, AgentModel.WONDER).map { it.model },
    )
    assertEquals(
      listOf("meta/muse-wonder", "meta/muse-spark-1.3", "meta/muse-spark-1.2"),
      chain.resolve(LlmProvider.OPENROUTER, AgentModel.WONDER).map { it.model },
    )
  }

  @Test
  fun `agent model SPARK pulls forward only the best Spark and beats the app-wide choice`() {
    val chain = twoRoutes(preferred = "muse-wonder-1.0")
    // The user's app-wide pick is Wonder, the agent wants Spark: the agent wins, Wonder stays as fallback.
    assertEquals(
      listOf("muse-spark-1.3", "muse-wonder-1.0", "muse-spark-1.2"),
      chain.resolve(LlmProvider.META, AgentModel.SPARK).map { it.model },
    )
  }

  @Test
  fun `no agent model leaves the chain exactly as before`() {
    val chain = twoRoutes(preferred = "muse-wonder-1.0")
    assertEquals(chain.resolve(LlmProvider.META), chain.resolve(LlmProvider.META, null))
    assertEquals(
      listOf("muse-wonder-1.0", "muse-spark-1.3", "muse-spark-1.2"),
      chain.resolve(LlmProvider.META).map { it.model },
    )
  }

  @Test
  fun `an agent model missing from the active catalog falls back to the default chain`() {
    val chain = chainOf(config("meta/muse-spark-1.3"), config("meta/muse-spark-1.2"))
    assertEquals(
      listOf("meta/muse-spark-1.3", "meta/muse-spark-1.2"),
      chain.resolve(LlmProvider.OPENROUTER, AgentModel.WONDER).map { it.model },
    )
  }

  @Test
  fun `agent model never resurrects a contributor model`() {
    val chain = chainOf(config("meta/muse-wonder-contributor"), config("meta/muse-spark-1.3"))
    assertEquals(listOf("meta/muse-spark-1.3"), chain.resolve(LlmProvider.OPENROUTER, AgentModel.WONDER).map { it.model })
  }

  @Test
  fun `unknown stored key maps to no agent model`() {
    assertEquals(AgentModel.WONDER, AgentModel.fromKey("WONDER"))
    assertEquals(null, AgentModel.fromKey("GPT_5"))
    assertEquals(null, AgentModel.fromKey(null))
  }
}
