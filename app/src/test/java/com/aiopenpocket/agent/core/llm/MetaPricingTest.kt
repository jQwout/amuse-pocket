package com.aiopenpocket.agent.core.llm

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [metaListPriceUsd] — the price of a direct Meta Model API call from the price list, since Meta does not send it. */
class MetaPricingTest {

  @Test
  fun `cached prompt tokens are billed at the cached rate`() {
    val usage = SseUsage(
      promptTokens = 1_000_000,
      completionTokens = 1_000_000,
      promptTokensDetails = PromptTokensDetails(cachedTokens = 400_000),
    )
    // 600k × $1.25 + 400k × $0.15 + 1M × $4.25 per million = 0.75 + 0.06 + 4.25
    assertEquals(5.06, metaListPriceUsd("muse-spark-1.3", usage)!!, 1e-9)
  }

  /** A cache larger than the prompt itself is a glitch in the provider's data, not a negative price. */
  @Test
  fun `cached count above the prompt is clamped`() {
    val usage = SseUsage(promptTokens = 100, promptTokensDetails = PromptTokensDetails(cachedTokens = 500))
    assertEquals(100 * 0.20 / 1_000_000, metaListPriceUsd("muse-wonder-1.0", usage)!!, 1e-12)
  }

  /** Models without a line in the price list are "price unknown", not a made-up number. */
  @Test
  fun `unknown model has no price`() {
    assertNull(metaListPriceUsd("muse-spark-9.9", SseUsage(promptTokens = 10)))
  }
}
