package com.aiopenpocket.data.repository

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-wide model picker is gone (31 Oct 2026). A person who had picked a model earlier has
 * `selected_model_META` on disk; nothing reads it any more, but it must not stay either, or it would look like
 * a live setting with no screen to undo it. [purgeObsoleteKeys] runs when the encrypted prefs open; the
 * encrypted file itself cannot be built on the JVM, so the same function is exercised on plain prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ObsoleteSettingsKeysTest {

  private val prefs = ApplicationProvider.getApplicationContext<Context>()
    .getSharedPreferences("obsolete_keys_test", Context.MODE_PRIVATE)

  @Test
  fun `a stored Meta model choice is erased and the key is left alone`() {
    prefs.edit().clear().putString("selected_model_META", "muse-wonder-1.0").putString("api_key_META", "meta-key").commit()

    purgeObsoleteKeys(prefs)

    assertFalse(prefs.contains("selected_model_META"))
    assertEquals("meta-key", prefs.getString("api_key_META", null))
  }

  @Test
  fun `the earlier removed routes are still erased`() {
    prefs.edit().clear()
      .putString("api_key_NVIDIA", "x").putString("selected_provider", "NVIDIA")
      .putString("api_key_OPENROUTER", "y").putString("selected_model", "meta/muse-spark-1.3")
      .commit()

    purgeObsoleteKeys(prefs)

    assertTrue(prefs.all.isEmpty())
  }
}
