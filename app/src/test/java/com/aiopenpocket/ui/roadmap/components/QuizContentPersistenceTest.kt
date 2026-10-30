package com.aiopenpocket.ui.roadmap.components

// @trace ai-academy-onboarding-5d51bb
// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiopenpocket.ui.roadmap.QuizQuestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reported defect: pick a quiz answer, leave the lesson, come back - the quiz was unanswered again. Leaving
 * the screen drops the composition's saved state, so the answers have to come back from [QuizContent.savedState].
 * "Leaving" is simulated by removing QuizContent from the composition and showing a brand-new one.
 */
@RunWith(RobolectricTestRunner::class)
// A tall screen: the quiz is a plain Column, so the second question must fit to be clicked.
@Config(sdk = [36], qualifiers = "w360dp-h2400dp")
class QuizContentPersistenceTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val questions = listOf(
    QuizQuestion("First?", listOf("one", "two", "three"), correctIndex = 1, explanation = "because first"),
    QuizQuestion("Second?", listOf("yes", "no"), correctIndex = 0, explanation = "because second"),
  )

  @Test
  fun `an answer survives leaving the lesson and coming back`() {
    var onScreen by mutableStateOf(true)
    var stored: String? = null
    var advanced = 0
    composeTestRule.setContent {
      if (onScreen) {
        QuizContent(
          questions = questions,
          onAllAnswered = { advanced++ },
          savedState = stored,
          onStateChanged = { stored = it },
        )
      }
    }

    composeTestRule.onNodeWithText("two").performClick()
    composeTestRule.onNodeWithText("because first").assertIsDisplayed()
    assertEquals("0:1", stored)

    // Leave the lesson (the quiz leaves the composition, its saved state goes with it) and return.
    composeTestRule.runOnIdle { onScreen = false }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { onScreen = true }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("because first").assertIsDisplayed()
    assertEquals(0, advanced)
  }

  @Test
  fun `a completed quiz that comes back does not advance the lesson a second time`() {
    var advanced = 0
    composeTestRule.setContent {
      QuizContent(
        questions = questions,
        onAllAnswered = { advanced++ },
        savedState = "0:1,1:0",
      )
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("because first").assertIsDisplayed()
    composeTestRule.onNodeWithText("because second").assertIsDisplayed()
    // The first pass already moved the cursor; firing again would skip a step.
    assertEquals(0, advanced)
  }

  @Test
  fun `finishing a fresh quiz still advances once and reports every pick`() {
    var advanced = 0
    val reported = mutableListOf<String?>()
    composeTestRule.setContent {
      QuizContent(
        questions = questions,
        onAllAnswered = { advanced++ },
        onStateChanged = { reported += it },
      )
    }

    composeTestRule.onNodeWithText("two").performClick()
    composeTestRule.onNodeWithText("yes").performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf<String?>("0:1", "0:1,1:0"), reported)
    assertEquals(1, advanced)
  }
}
