package com.aiopenpocket.agent.core.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aiopenpocket.testing.testDataStore

/**
 * A round trip of the chosen chat font preset through a real DataStore (Robolectric) — the way the other
 * user settings are stored. An empty store and a garbage value must give [ChatFontScale.DEFAULT].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserPreferencesChatFontTest {

  private val repo = UserPreferencesRepository(testDataStore(ApplicationProvider.getApplicationContext<Context>()))

  @Test
  fun `default is MEDIUM before anything is stored`() = runTest {
    // A fresh Robolectric context: the key has not been written yet.
    assertEquals(ChatFontScale.MEDIUM, repo.getChatFontScale())
  }

  @Test
  fun `set then get round-trips the chosen preset`() = runTest {
    repo.setChatFontScale(ChatFontScale.XLARGE)
    assertEquals(ChatFontScale.XLARGE, repo.getChatFontScale())
    assertEquals(ChatFontScale.XLARGE, repo.chatFontScale.first())
  }

  @Test
  fun `overwriting replaces the previous preset`() = runTest {
    repo.setChatFontScale(ChatFontScale.LARGE)
    repo.setChatFontScale(ChatFontScale.SMALL)
    assertEquals(ChatFontScale.SMALL, repo.getChatFontScale())
  }
}
