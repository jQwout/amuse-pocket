package com.aiopenpocket.ui.chat

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentence for each way the "segment" step can fail: each tells the learner what to do about it. */
class SegmentationMessagesTest {

  private val cyrillic = Regex("[\\u0400-\\u04FF]")

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  @Test
  fun `every failure has a sentence in both languages and the English one is English`() {
    for (reason in SegmentationFailure.values()) {
      AppLanguage.code = AppLanguage.RU
      val ru = segmentationProblemText(reason)
      AppLanguage.code = AppLanguage.EN
      val en = segmentationProblemText(reason)

      assertTrue("$reason has no Russian text", ru.isNotBlank() && cyrillic.containsMatchIn(ru))
      assertTrue("$reason has no English text", en.isNotBlank())
      assertFalse("$reason: Russian on the English side: $en", cyrillic.containsMatchIn(en))
    }
  }

  @Test
  fun `the failures a learner can fix name the fix`() {
    AppLanguage.code = AppLanguage.EN
    // No key: where to put it, and that the step may be skipped.
    val noKey = segmentationProblemText(SegmentationFailure.NO_KEY)
    assertTrue(noKey, noKey.contains("Settings") && noKey.contains("API KEY") && noKey.contains("skip"))
    // No billing is Meta's side, and it is SAM's price that makes it matter.
    assertTrue(segmentationProblemText(SegmentationFailure.BILLING).contains("billing_not_configured"))
    assertTrue(segmentationProblemText(SegmentationFailure.NO_MODEL_ACCESS).contains("sam-3.1"))
    // A bad word is retyped on the card.
    assertTrue(segmentationProblemText(SegmentationFailure.BAD_CONCEPT).contains("one thing"))
  }

  @Test
  fun `different causes get different sentences`() {
    AppLanguage.code = AppLanguage.EN
    val texts = SegmentationFailure.values().map(::segmentationProblemText)
    // BAD_RESPONSE, INCOMPLETE and REFUSED deliberately share one: the learner's move is the same.
    assertEquals(SegmentationFailure.values().size - 2, texts.toSet().size)
  }
}
