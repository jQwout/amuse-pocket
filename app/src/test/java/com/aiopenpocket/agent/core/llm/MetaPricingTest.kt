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

  /** SAM is billed per picture, so the cost is the count of pictures times the price of one: $2.50 per 1000. */
  @Test
  fun `SAM costs a quarter of a cent per picture`() {
    assertEquals(0.0025, samCostUsd(1), 1e-12)
    assertEquals(0.0075, samCostUsd(3), 1e-12)
    // A provider that says 0 pictures was processed is believed: nothing was billed.
    assertEquals(0.0, samCostUsd(0), 1e-12)
  }

  /** No usage in the answer: a still image is one picture. The caller marks that row as estimated. */
  @Test
  fun `SAM without a picture count is priced as one picture`() {
    assertEquals(0.0025, samCostUsd(null), 1e-12)
  }

  /** Models without a line in the price list are "price unknown", not a made-up number. */
  @Test
  fun `unknown model has no price`() {
    assertNull(metaListPriceUsd("muse-spark-9.9", SseUsage(promptTokens = 10)))
  }
}
