package com.aiopenpocket.ui.agents

// @trace user-agent-builder-848153

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a screen reader and `uiautomator dump` see of the "Create agent" button. They read the MERGED
 * semantics tree, so every lookup here is merged (the default) — an unmerged lookup finds the label in a child
 * that the services never see, which is exactly how this defect hid: the visible text was there, the button
 * node had none (Android 14, 28 Oct 2026).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CreateAgentFabA11yTest {
  @get:Rule val composeTestRule = createComposeRule()

  @After fun restoreLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private val isButton = SemanticsMatcher("Role is Button") {
    it.config.getOrNull(SemanticsProperties.Role) == Role.Button
  }

  @Test
  fun fab_is_a_named_clickable_button_in_english() {
    AppLanguage.code = AppLanguage.EN
    var clicks = 0
    composeTestRule.setContent { CreateAgentFab(onClick = { clicks++ }) }

    composeTestRule.onNodeWithText("Create agent")
      .assert(isButton)
      .assertHasClickAction()
      .performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun fab_is_a_named_clickable_button_in_russian() {
    AppLanguage.code = AppLanguage.RU
    composeTestRule.setContent { CreateAgentFab(onClick = {}) }

    composeTestRule.onNodeWithText("Создать агента")
      .assert(isButton)
      .assertHasClickAction()
  }

  @Test
  fun label_is_announced_exactly_once() {
    // If a future Material release starts merging the child label itself, this fails instead of the screen
    // reader quietly reading "Create agent, Create agent".
    AppLanguage.code = AppLanguage.EN
    composeTestRule.setContent { CreateAgentFab(onClick = {}) }

    val texts = composeTestRule.onNodeWithText("Create agent")
      .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
      ?.map { it.text }
    assertEquals(listOf("Create agent"), texts)
  }

  @Test
  fun fab_survives_inside_the_screen_scaffold() {
    // The real frame: AcademyCollapsingScaffold + glass chrome around the button, as on the device.
    AppLanguage.code = AppLanguage.EN
    composeTestRule.setContent { AgentsPreviewScaffold() }

    composeTestRule.onNodeWithText("Create agent")
      .assert(isButton)
      .assert(hasClickAction())
      .assert(hasText("Create agent"))
  }
}
