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
      LlmProvider.NIM to listOf(config(LlmProvider.NIM)),
      LlmProvider.GROQ to listOf(config(LlmProvider.GROQ)),
      LlmProvider.GITHUB to listOf(config(LlmProvider.GITHUB)),
    ),
  )

  @Test
  fun `AUTO resolves NIM then GROQ then GITHUB`() {
    val resolved = chain.resolve(LlmProvider.AUTO).map { it.name }
    assertEquals(listOf(LlmProvider.NIM, LlmProvider.GROQ, LlmProvider.GITHUB), resolved)
  }

  @Test
  fun `AUTO preserves NIM model order by priority`() {
    val multiNim = ProviderFallbackChain(
      mapOf(
        LlmProvider.NIM to listOf(
          config(LlmProvider.NIM).copy(model = "model-1"),
          config(LlmProvider.NIM).copy(model = "model-2"),
          config(LlmProvider.NIM).copy(model = "model-3"),
        ),
        LlmProvider.GROQ to listOf(config(LlmProvider.GROQ)),
      ),
    )
    val resolved = multiNim.resolve(LlmProvider.AUTO).map { it.model }
    assertEquals(listOf("model-1", "model-2", "model-3", "model-GROQ"), resolved)
  }

  @Test
  fun `specific provider resolves to only that provider`() {
    val resolved = chain.resolve(LlmProvider.GROQ).map { it.name }
    assertEquals(listOf(LlmProvider.GROQ), resolved)
  }

  @Test
  fun `missing provider resolves to empty list`() {
    val partial = ProviderFallbackChain(mapOf(LlmProvider.GROQ to listOf(config(LlmProvider.GROQ))))
    assertTrue(partial.resolve(LlmProvider.NIM).isEmpty())
  }

  @Test
  fun `AUTO skips providers absent from config`() {
    val partial = ProviderFallbackChain(mapOf(LlmProvider.GROQ to listOf(config(LlmProvider.GROQ))))
    val resolved = partial.resolve(LlmProvider.AUTO).map { it.name }
    assertEquals(listOf(LlmProvider.GROQ), resolved)
  }
}
