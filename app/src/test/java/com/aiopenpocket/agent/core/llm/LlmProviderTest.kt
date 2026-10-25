package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Два предиката, на которых держится «всё на Muse»: какой ключ OpenRouter считать настоящим и какую
 * модель пускать в выбор и в цепочку.
 */
class LlmProviderTest {

  /** Плейсхолдер из `.env.example` — не ключ: уйди он в запрос, вместо предупреждения в Настройках был бы 401. */
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
    // Llama на OpenRouter обслуживают сторонние хостеры, а не Meta.
    assertFalse(isMetaModel("meta-llama/llama-4-maverick"))
    assertFalse(isMetaModel("anthropic/claude-sonnet-5"))
    assertFalse(isMetaModel(""))
  }
}
