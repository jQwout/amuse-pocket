package com.aiopenpocket.ui.settings

// @trace app-settings-beb470

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings has a way to the Profile screen (the owner, 31 Oct 2026: "where is the information about the
 * user"). The card is the first thing on the screen, and a tap must reach the navigation callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileEntryCardTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun tapping_the_card_opens_the_profile() {
    var opened = 0
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) { ProfileEntryCard(onClick = { opened++ }) }
    }

    composeTestRule.onNodeWithTag("settings_profile_entry").performClick()

    assertEquals(1, opened)
  }
}
