package com.aiopenpocket.agent.core.llm

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider facts "everything on the Meta Model API" rests on.
 */
class LlmProviderTest {

  /**
   * The owner removed OpenRouter on Oct 30, 2026 (the hackathon's first screening wants the Meta Model API).
   * A second value coming back would also bring back a second recipient for the person's data, so it must
   * be a decision with a failing test in front of it, not a drive-by edit.
   */
  @Test
  fun `Meta is the only provider`() {
    assertEquals(listOf("META"), LlmProvider.entries.map { it.name })
  }

  /** Where the single catalog points: a request must never be addressed to anyone but Meta. */
  @Test
  fun `the catalog base address is the Meta Model API`() {
    assertEquals("https://api.meta.ai/v1", ModelCatalog.META_BASE_URL)
    assertEquals(ModelCatalog.META_BASE_URL, ModelCatalog().baseUrl)
  }

  /** The vendor-trained `-contributor` versions never reach the picker or the chain. */
  @Test
  fun `contributor versions are recognised`() {
    assertTrue(trainsOnUserData("muse-spark-1.3-contributor"))
    assertFalse(trainsOnUserData("muse-spark-1.3"))
  }
}
