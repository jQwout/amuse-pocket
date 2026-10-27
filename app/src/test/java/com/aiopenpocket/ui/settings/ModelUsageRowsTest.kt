package com.aiopenpocket.ui.settings

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "BY MODEL" list of the weekly spend card shows the top three models by tokens. SAM is billed per picture, has
 * no tokens, and would always rank last — so in a fresh demo, where it is the one call that actually cost money,
 * it would not be on the card the demo video shows (criterion P5).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ModelUsageRowsTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun tokens(model: String, tokens: Long, cost: Double? = 0.01) =
    UsageSummary.ModelUsage(model = model, calls = 3, tokens = tokens, costUsd = cost)

  private val sam = UsageSummary.ModelUsage(model = "sam-3.1", calls = 1, tokens = 0, costUsd = 0.0025)

  @Test
  fun `a model billed per picture is recognised by money without tokens`() {
    assertTrue(sam.billedPerPicture)
    // Tokens make it an ordinary row; no price makes it unbillable, not a per-picture one.
    assertFalse(tokens("muse-spark-1.3", 1_000).billedPerPicture)
    assertFalse(tokens("free-model", 0, cost = null).billedPerPicture)
    assertFalse(tokens("free-model", 0, cost = 0.0).billedPerPicture)
  }

  @Test
  fun `the per-picture model is shown even behind more than three token models`() {
    val ranked = listOf(
      tokens("meta/muse-spark-1.3", 90_000),
      tokens("meta/muse-image", 40_000),
      tokens("meta/muse-spark-1.2", 20_000),
      tokens("meta/muse-spark-1.1", 10_000),
      sam,
    )

    val rows = modelUsageRows(ranked)

    assertEquals(listOf("meta/muse-spark-1.3", "meta/muse-image", "meta/muse-spark-1.2", "sam-3.1"), rows.map { it.model })
  }

  @Test
  fun `a short list is shown whole`() {
    assertEquals(listOf("sam-3.1"), modelUsageRows(listOf(sam)).map { it.model })
    assertTrue(modelUsageRows(emptyList()).isEmpty())
  }

  @Test
  fun `a model with no tokens and no price is not promoted`() {
    val ranked = listOf(tokens("a", 5), tokens("b", 4), tokens("c", 3), tokens("d", 2), tokens("silent", 0, cost = null))

    assertEquals(listOf("a", "b", "c"), modelUsageRows(ranked).map { it.model })
  }

  @Test
  fun `a per-picture row reads calls and dollars, an ordinary one calls and tokens`() {
    assertEquals("1 · \$0.0025", modelUsageValue(sam))
    assertEquals("3 · 12.3k", modelUsageValue(tokens("meta/muse-spark-1.3", 12_300)))
  }

  /** On the card itself: the row is on screen with its money, not just in the list a function returns. */
  @Test
  fun `the spend card shows the SAM row`() {
    AppLanguage.code = AppLanguage.EN
    val usage = UsageSummary(
      calls = 9,
      promptTokens = 80_000,
      completionTokens = 6_000,
      costUsd = 0.12,
      estimatedCalls = 1,
      backgroundCalls = 0,
      backgroundTokens = 0,
      byModel = listOf(
        tokens("meta/muse-spark-1.3", 60_000),
        tokens("meta/muse-image", 12_000),
        tokens("meta/muse-spark-1.2", 8_000),
        tokens("meta/muse-spark-1.1", 6_000),
        sam,
      ),
    )

    composeTestRule.setContent { MyApplicationTheme(mode = ThemeMode.DARK) { LlmUsageCard(usage) } }

    composeTestRule.onNodeWithText("sam-3.1").assertExists()
    composeTestRule.onNodeWithText("1 · \$0.0025").assertExists()
    // The fourth token model is the one that stays off.
    composeTestRule.onNodeWithText("muse-spark-1.1").assertDoesNotExist()
  }
}
