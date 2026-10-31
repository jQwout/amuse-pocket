package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Profile screen's contract with its callers, on the REAL [ProfileContent] fed fake state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ProfileScreenTest {
  @get:Rule val composeTestRule = createComposeRule()

  @After fun restoreLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private val sister = Fact("f1", "family.sister", "Лидия", "general", 1L, 1L)

  private class Calls {
    var settings = 0
    var graph = 0
    var save = 0
    var confirm = 0
    var cancel = 0
    var requested: Fact? = null
    val edits = mutableListOf<Pair<ProfileField, String>>()
  }

  private fun show(state: ProfileUiState, calls: Calls = Calls()): Calls {
    composeTestRule.setContent {
      ProfileContent(
        state = state,
        onNavigateBack = {},
        onOpenSettings = { calls.settings++ },
        onOpenMemoryGraph = { calls.graph++ },
        onFieldChange = { field, value -> calls.edits += field to value },
        onSave = { calls.save++ },
        onRequestDelete = { calls.requested = it },
        onConfirmDelete = { calls.confirm++ },
        onCancelDelete = { calls.cancel++ },
      )
    }
    return calls
  }

  private fun loaded(
    profile: UserProfile = UserProfile(name = "Анна", preferredAddress = "Аня", age = "31"),
    facts: List<FactSection> = emptyList(),
    showReligion: Boolean = false,
  ) = ProfileUiState(
    loaded = true,
    draft = profile,
    persisted = profile,
    showReligion = showReligion,
    factSections = facts,
  )

  @Test
  fun gear_in_the_top_bar_opens_settings() {
    val calls = show(loaded())

    composeTestRule.onNodeWithTag("profile_settings_button").performClick()

    assertEquals(1, calls.settings)
  }

  @Test
  fun open_memory_graph_link_is_there() {
    val calls = show(loaded())

    composeTestRule.onNodeWithTag("profile_open_memory_graph").performScrollTo().performClick()

    assertEquals(1, calls.graph)
  }

  @Test
  fun personal_data_is_shown_at_once_and_empty_fields_say_not_set() {
    AppLanguage.code = AppLanguage.EN
    show(loaded(profile = UserProfile(name = "Anna", preferredAddress = "")))

    composeTestRule.onNodeWithText("Anna").assertIsDisplayed()
    // Four fields are empty (address, age, gender, about me): a quiet hint in each, not an error.
    composeTestRule.onAllNodesWithTag("profile_field_address").assertCountEquals(1)
    composeTestRule.onNodeWithTag("profile_field_address").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("not set", useUnmergedTree = true).assertCountEquals(4)
  }

  @Test
  fun religion_field_is_absent_unless_it_was_set() {
    show(loaded(showReligion = false))
    composeTestRule.onAllNodesWithTag("profile_field_religion").assertCountEquals(0)
  }

  @Test
  fun religion_field_is_editable_when_it_was_set() {
    val calls = show(loaded(profile = UserProfile("Анна", "Аня", religion = "буддизм"), showReligion = true))

    composeTestRule.onNodeWithTag("profile_field_religion").performScrollTo().performTextInput("!")

    assertEquals(ProfileField.RELIGION, calls.edits.single().first)
  }

  @Test
  fun typing_reports_the_field_that_was_edited() {
    val calls = show(loaded())

    composeTestRule.onNodeWithTag("profile_field_name").performTextInput("а")

    assertEquals(ProfileField.NAME, calls.edits.single().first)
  }

  @Test
  fun save_is_available_only_when_something_changed() {
    show(loaded())
    composeTestRule.onNodeWithTag("profile_save_button").performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun save_button_saves_a_changed_profile() {
    val base = UserProfile("Анна", "Аня")
    val calls = show(loaded(profile = base).copy(draft = base.copy(name = "Мария")))

    composeTestRule.onNodeWithTag("profile_save_button").performScrollTo().assertIsEnabled().performClick()

    assertEquals(1, calls.save)
  }

  @Test
  fun each_remembered_fact_asks_before_it_is_forgotten() {
    val calls = show(loaded(facts = listOf(FactSection(FactGroup.OTHER, listOf(sister)))))

    composeTestRule.onNodeWithText("Лидия").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_fact_delete_f1").performScrollTo().performClick()

    assertEquals(sister, calls.requested)
    assertEquals("the tap alone must not delete", 0, calls.confirm)
  }

  @Test
  fun the_confirmation_dialog_confirms_and_cancels() {
    val calls = show(loaded().copy(pendingDelete = sister))

    composeTestRule.onNodeWithTag("profile_forget_cancel").performClick()
    composeTestRule.onNodeWithTag("profile_forget_confirm").performClick()

    assertEquals(1, calls.cancel)
    assertEquals(1, calls.confirm)
  }

  @Test
  fun no_dialog_without_a_pending_fact() {
    show(loaded(facts = listOf(FactSection(FactGroup.OTHER, listOf(sister)))))

    composeTestRule.onAllNodesWithTag("profile_forget_confirm").assertCountEquals(0)
  }

  @Test
  fun empty_memory_says_so_gently() {
    AppLanguage.code = AppLanguage.EN
    show(loaded())

    composeTestRule.onNodeWithText("Nothing remembered yet").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun spend_card_is_on_the_screen() {
    AppLanguage.code = AppLanguage.EN
    show(loaded())

    // No usage yet: the same gentle "nothing to report" card Settings shows.
    composeTestRule.onNodeWithText("Nothing to report yet").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun section_titles_follow_the_language() {
    AppLanguage.code = AppLanguage.EN
    show(loaded())

    composeTestRule.onNodeWithText("PERSONAL DATA").assertIsDisplayed()
    composeTestRule.onNodeWithText("WHAT THE ASSISTANT REMEMBERS").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("USAGE THIS WEEK").performScrollTo().assertIsDisplayed()
  }
}
