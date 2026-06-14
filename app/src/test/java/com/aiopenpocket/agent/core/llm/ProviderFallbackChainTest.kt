package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackChainTest {

  private fun config(p: LlmProvider) = ProviderConfig(
    name = p,
    baseUrl = "https://example/$p",
    model = "model-$p",
    apiKeyResolver = { "key" },
  )

  private val chain = ProviderFallbackChain(
    mapOf(
      LlmProvider.NIM to config(LlmProvider.NIM),
      LlmProvider.GROQ to config(LlmProvider.GROQ),
      LlmProvider.GITHUB to config(LlmProvider.GITHUB),
    ),
  )

  @Test
  fun `AUTO resolves NIM then GROQ then GITHUB`() {
    val resolved = chain.resolve(LlmProvider.AUTO).map { it.name }
    assertEquals(listOf(LlmProvider.NIM, LlmProvider.GROQ, LlmProvider.GITHUB), resolved)
  }

  @Test
  fun `specific provider resolves to only that provider`() {
    val resolved = chain.resolve(LlmProvider.GROQ).map { it.name }
    assertEquals(listOf(LlmProvider.GROQ), resolved)
  }

  @Test
  fun `missing provider resolves to empty list`() {
    val partial = ProviderFallbackChain(mapOf(LlmProvider.GROQ to config(LlmProvider.GROQ)))
    assertTrue(partial.resolve(LlmProvider.NIM).isEmpty())
  }

  @Test
  fun `AUTO skips providers absent from config`() {
    val partial = ProviderFallbackChain(mapOf(LlmProvider.GROQ to config(LlmProvider.GROQ)))
    val resolved = partial.resolve(LlmProvider.AUTO).map { it.name }
    assertEquals(listOf(LlmProvider.GROQ), resolved)
  }
}
