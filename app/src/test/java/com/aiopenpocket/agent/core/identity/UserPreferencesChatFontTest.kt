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
 * Round-trip выбранного пресета шрифта чата через реальный DataStore (Robolectric) — как хранят
 * остальные user-настройки. Пустой store и мусорное значение обязаны давать [ChatFontScale.DEFAULT].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserPreferencesChatFontTest {

  private val repo = UserPreferencesRepository(testDataStore(ApplicationProvider.getApplicationContext<Context>()))

  @Test
  fun `default is MEDIUM before anything is stored`() = runTest {
    // Свежий Robolectric-контекст: ключ ещё не писали.
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
