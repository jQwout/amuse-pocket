package com.aiopenpocket.data.repository

import android.content.Context
import com.aiopenpocket.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeRepositoryTest {

  private val context: Context get() = RuntimeEnvironment.getApplication()

  private fun clearPrefs() =
    context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit().clear().commit()

  @Test fun defaultsToSystemWhenEmpty() {
    clearPrefs()
    assertEquals(ThemeMode.SYSTEM, ThemeRepository(context).mode.value)
  }

  @Test fun setModeUpdatesFlowImmediately() {
    clearPrefs()
    val repo = ThemeRepository(context)
    repo.setMode(ThemeMode.DARK)
    assertEquals(ThemeMode.DARK, repo.mode.value)
  }

  @Test fun persistsAcrossInstances() {
    clearPrefs()
    ThemeRepository(context).setMode(ThemeMode.LIGHT)
    // A new instance reads the already saved value from prefs.
    assertEquals(ThemeMode.LIGHT, ThemeRepository(context).mode.value)
  }

  @Test fun unknownStoredValueDegradesToSystem() {
    context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
      .edit().putString("theme_mode", "PLAID").commit()
    assertEquals(ThemeMode.SYSTEM, ThemeRepository(context).mode.value)
  }
}
