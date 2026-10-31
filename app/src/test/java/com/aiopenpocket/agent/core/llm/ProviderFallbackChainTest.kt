package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackChainTest {

  private fun config(model: String, supportsTools: Boolean = true) = ProviderConfig(
    name = LlmProvider.META,
    baseUrl = "https://example/meta",
    model = model,
    supportsTools = supportsTools,
    apiKeyResolver = { "key" },
  )

  private fun chainOf(vararg configs: ProviderConfig) = ProviderFallbackChain(
    configs = mapOf(LlmProvider.META to configs.toList()),
  )

  @Test
  fun `preserves model order by priority`() {
    val chain = chainOf(config("model-1"), config("model-2"), config("model-3"))
    assertEquals(listOf("model-1", "model-2", "model-3"), chain.resolve(LlmProvider.META).map { it.model })
  }

  /**
   * Without a ceiling one wrong key would mean an attempt on every catalog model in a row on every turn:
   * the phone heats up, the user waits, and the answer is still "did not work".
   */
  @Test
  fun `the attempt chain is capped`() {
    val huge = chainOf(*(1..300).map { config("vendor/model-$it") }.toTypedArray())
    assertEquals(5, huge.resolve(LlmProvider.META).size)
  }

  /**
   * The "function-calling models only" gate stands here, not only in config assembly: every way of building
   * the chain goes through resolve — the live catalog, a hand-edited asset and a cache written by a previous
   * version of the app.
   */
  @Test
  fun `models without tool support never enter the chain`() {
    val chain = chainOf(config("no-tools", supportsTools = false), config("with-tools"))
    assertEquals(listOf("with-tools"), chain.resolve(LlmProvider.META).map { it.model })
  }

  /** A cache written by a version without the filter may still hold a contributor model; it never enters the chain. */
  @Test
  fun `a model that trains on user data never enters the chain`() {
    val chain = chainOf(config("muse-spark-1.3-contributor"), config("muse-spark-1.3"))
    assertEquals(listOf("muse-spark-1.3"), chain.resolve(LlmProvider.META).map { it.model })
  }

  @Test
  fun `empty catalog resolves to empty list`() {
    assertTrue(chainOf().resolve(LlmProvider.META).isEmpty())
  }

  // --- the agent's own model (AgentDefinition.model) -------------------------------------------

  private fun metaConfig(model: String) = config(model).copy(name = LlmProvider.META)

  private fun metaChain() = ProviderFallbackChain(
    configs = mapOf(
      // The Meta catalog: ids without a vendor prefix, already sorted by priority.
      LlmProvider.META to listOf("muse-spark-1.3", "muse-spark-1.2", "muse-wonder-1.0").map(::metaConfig),
    ),
  )

  @Test
  fun `agent model WONDER goes first, whatever version the catalog lists`() {
    val chain = metaChain()
    assertEquals(
      listOf("muse-wonder-1.0", "muse-spark-1.3", "muse-spark-1.2"),
      chain.resolve(LlmProvider.META, AgentModel.WONDER).map { it.model },
    )
  }

  @Test
  fun `agent model SPARK pulls forward only the best Spark`() {
    val chain = metaChain()
    // Only the newest Spark moves to the front; the rest keep the catalog's priority order.
    assertEquals(
      listOf("muse-spark-1.3", "muse-spark-1.2", "muse-wonder-1.0"),
      chain.resolve(LlmProvider.META, AgentModel.SPARK).map { it.model },
    )
  }

  @Test
  fun `no agent model leaves the chain exactly as before`() {
    val chain = metaChain()
    assertEquals(chain.resolve(LlmProvider.META), chain.resolve(LlmProvider.META, null))
    assertEquals(
      listOf("muse-spark-1.3", "muse-spark-1.2", "muse-wonder-1.0"),
      chain.resolve(LlmProvider.META).map { it.model },
    )
  }

  @Test
  fun `an agent model missing from the active catalog falls back to the default chain`() {
    val chain = chainOf(config("muse-spark-1.3"), config("muse-spark-1.2"))
    assertEquals(
      listOf("muse-spark-1.3", "muse-spark-1.2"),
      chain.resolve(LlmProvider.META, AgentModel.WONDER).map { it.model },
    )
  }

  @Test
  fun `agent model never resurrects a contributor model`() {
    val chain = chainOf(config("muse-wonder-contributor"), config("muse-spark-1.3"))
    assertEquals(listOf("muse-spark-1.3"), chain.resolve(LlmProvider.META, AgentModel.WONDER).map { it.model })
  }

  @Test
  fun `a Spark pick never lands on the open-weight Spark`() {
    assertTrue(AgentModel.SPARK.matches("muse-spark-1.3"))
    assertTrue(!AgentModel.SPARK.matches("muse-spark-open"))
  }

  @Test
  fun `unknown stored key maps to no agent model`() {
    assertEquals(AgentModel.WONDER, AgentModel.fromKey("WONDER"))
    assertEquals(null, AgentModel.fromKey("GPT_5"))
    assertEquals(null, AgentModel.fromKey(null))
  }
}
