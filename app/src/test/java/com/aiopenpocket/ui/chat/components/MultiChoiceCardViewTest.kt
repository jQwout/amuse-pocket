package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.MultiChoiceSelection
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `multi_choice` card and the review card as the person meets them in the builder chat. What matters is
 * what comes OUT of the card (the user's message, the saved state) and what a tap can still change after
 * the answer went to the model.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MultiChoiceCardViewTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private val card = DrivenUiCard.MultiChoiceCard(
    question = "What may it use?",
    options = listOf("Memory", "Reminders", "Calendar"),
    preselected = setOf(0),
    min = 1,
    max = 2,
  )

  private class Recorder {
    val sent = mutableListOf<String>()
    val states = mutableListOf<String?>()
  }

  private fun render(card: DrivenUiCard.MultiChoiceCard = this.card, state: String? = null, recorder: Recorder = Recorder()): Recorder {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) {
        MultiChoiceCardView(
          card = card,
          cardState = state,
          onCardStateChanged = { recorder.states += it },
          onSubmit = { recorder.sent += it },
        )
      }
    }
    return recorder
  }

  @Test
  fun `preselected options start ticked`() {
    render()

    composeTestRule.onNodeWithText("Memory").assertIsOn()
    composeTestRule.onNodeWithText("Reminders").assertIsOff()
  }

  @Test
  fun `next sends the ticked labels joined and saves the answered state`() {
    val recorder = render()

    composeTestRule.onNodeWithText("Calendar").performClick()
    composeTestRule.onNodeWithTag("multi_choice_next").performClick()

    assertEquals(listOf("Memory, Calendar"), recorder.sent)
    assertEquals(listOf(MultiChoiceSelection.encodeSubmitted(setOf(0, 2))), recorder.states)
  }

  @Test
  fun `ticks stop at max`() {
    render() // max = 2, Memory is already ticked

    composeTestRule.onNodeWithText("Reminders").performClick()
    composeTestRule.onNodeWithText("Calendar").performClick() // a third tick: ignored

    composeTestRule.onNodeWithText("Calendar").assertIsOff()
    composeTestRule.onNodeWithText("Reminders").assertIsOn()
  }

  @Test
  fun `next is disabled until min ticks are set`() {
    render(card.copy(preselected = emptySet()))

    composeTestRule.onNodeWithTag("multi_choice_next").assertIsNotEnabled()
    composeTestRule.onNodeWithText("Reminders").performClick()
    composeTestRule.onNodeWithTag("multi_choice_next").assertIsEnabled()
  }

  @Test
  fun `after next the card is locked and cannot send twice`() {
    val recorder = render()

    composeTestRule.onNodeWithTag("multi_choice_next").performClick()
    composeTestRule.onNodeWithText("Reminders").performClick() // locked: no tick, no second message

    composeTestRule.onNodeWithText("Reminders").assertIsOff()
    composeTestRule.onNodeWithText("Memory").assertIsOn()
    composeTestRule.onNodeWithTag("multi_choice_next").assertDoesNotExist()
    assertEquals(1, recorder.sent.size)
  }

  /** The person left the chat and came back: the saved state, not the card's defaults, decides what is shown. */
  @Test
  fun `an answered card from the saved state is locked with its ticks`() {
    val recorder = render(state = MultiChoiceSelection.encodeSubmitted(setOf(1, 2)))

    composeTestRule.onNodeWithText("Reminders").assertIsOn()
    composeTestRule.onNodeWithText("Calendar").assertIsOn()
    composeTestRule.onNodeWithText("Memory").assertIsOff() // the preselect is NOT applied over a saved answer
    composeTestRule.onNodeWithTag("multi_choice_next").assertDoesNotExist()
    composeTestRule.onNodeWithText("Memory").performClick()
    composeTestRule.onNodeWithText("Memory").assertIsOff()
    assertTrue(recorder.sent.isEmpty())
  }

  @Test
  fun `the review card opens the review screen of its agent`() {
    val opened = mutableListOf<String>()
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) {
        AgentReviewCardView(DrivenUiCard.AgentReviewCard("my-agent-1", "Budget buddy")) { opened += it }
      }
    }

    composeTestRule.onNodeWithText("Budget buddy").assertExists()
    composeTestRule.onNodeWithTag("agent_review_open").performClick()

    assertEquals(listOf("my-agent-1"), opened)
  }
}
