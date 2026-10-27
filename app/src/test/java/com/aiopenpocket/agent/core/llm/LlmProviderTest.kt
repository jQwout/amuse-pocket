package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two predicates "everything on Muse" rests on: which OpenRouter key counts as real and which model is
 * let into the picker and the chain.
 */
class LlmProviderTest {

  /** The placeholder from `.env.example` is not a key: if it went into a request, there would be a 401 instead of a warning in Settings. */
  @Test
  fun `placeholder and blank build keys are treated as no key`() {
    assertEquals("", bundledOpenRouterKey(""))
    assertEquals("", bundledOpenRouterKey(OPENROUTER_KEY_PLACEHOLDER))
    assertEquals("sk-or-v1-test", bundledOpenRouterKey("sk-or-v1-test"))
  }

  @Test
  fun `only Meta models pass the Muse gate`() {
    assertTrue(isMetaModel("meta/muse-spark-1.3"))
    assertTrue(isMetaModel("meta/muse-wonder"))
    // Llama on OpenRouter is served by third-party hosts, not by Meta.
    assertFalse(isMetaModel("meta-llama/llama-4-maverick"))
    assertFalse(isMetaModel("anthropic/claude-sonnet-5"))
    assertFalse(isMetaModel(""))
  }
}
