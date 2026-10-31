package com.aiopenpocket.ui.home

// @trace profile-screen-7354a7

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The round avatar on Home opens the Profile screen. It used to open Settings, and a person who came for their own
 * data found a key field instead (31 Oct 2026). [HomeScreen] itself pulls its ViewModels from DI, so the top bar
 * actions it hands to the app bar are rendered here on their own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class HomeProfileAvatarTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun avatar_opens_profile_and_nothing_else() {
    var profile = 0
    var conversations = 0
    composeTestRule.setContent {
      Row { HomeTopBarActions(onNavigateToConversations = { conversations++ }, onNavigateToProfile = { profile++ }) }
    }

    composeTestRule.onNodeWithTag("home_profile_avatar").performClick()

    assertEquals(1, profile)
    assertEquals(0, conversations)
  }
}
