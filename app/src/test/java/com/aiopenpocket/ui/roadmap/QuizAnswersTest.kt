package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb
// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wire form of a lesson quiz's answers (messages.cardState): it must round-trip and never trust stale data. */
class QuizAnswersTest {

  private val questions = listOf(
    QuizQuestion("q0", listOf("a", "b", "c"), correctIndex = 1, explanation = "e0"),
    QuizQuestion("q1", listOf("a", "b"), correctIndex = 0, explanation = "e1"),
  )

  @Test
  fun `answers round-trip through the stored string`() {
    val answers = mapOf(1 to 0, 0 to 2)
    assertEquals("0:2,1:0", QuizAnswers.encode(answers))
    assertEquals(answers, QuizAnswers.decode(QuizAnswers.encode(answers), questions))
  }

  @Test
  fun `a cleared quiz stores nothing and restores empty`() {
    assertNull(QuizAnswers.encode(emptyMap()))
    assertEquals(emptyMap<Int, Int>(), QuizAnswers.decode(null, questions))
    assertEquals(emptyMap<Int, Int>(), QuizAnswers.decode("", questions))
  }

  @Test
  fun `entries that no longer fit the lesson are dropped`() {
    // question 5 is gone, option 7 does not exist, option -1 and the garbage are not options at all.
    val decoded = QuizAnswers.decode("0:1,5:0,1:7,0:-1,x:y,3", questions)
    assertEquals(mapOf(0 to 1), decoded)
  }
}
