package com.aiopenpocket.data.repository

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Provider keys (OpenRouter and Meta Model API) and the model choice.
 *
 * An interface (the implementation is [EncryptedSettingsRepository]) not for the sake of an
 * "abstraction for the future", but because the only implementation fundamentally cannot be created
 * in a unit test: it brings up the Android Keystore, and Robolectric has none — `KeyStoreException:
 * AndroidKeyStore not found` comes on the very first access to the prefs. While this was a final class,
 * not a single test could cover SettingsViewModel.
 */
interface SettingsRepository {

  fun getApiKey(provider: String): String

  fun setApiKey(provider: String, key: String)

  /**
   * The specific Meta model on route [provider] (`LlmProvider.name`). "AUTO" — go by priority from the
   * catalog.
   *
   * Each route has its own choice: the same model has different ids on OpenRouter and on the direct
   * Meta Model API ("meta/muse-wonder" and "muse-wonder-1.0"). A shared choice would either be silently
   * lost when a Meta key is entered, or show a model in Settings the chain does not know.
   */
  fun getSelectedModel(provider: String = "OPENROUTER"): String

  fun setSelectedModel(model: String, provider: String = "OPENROUTER")

  companion object {
    /**
     * The EncryptedSharedPreferences file name. A constant because it is repeated verbatim in
     * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` — the file MUST be excluded from
     * backup: it is encrypted with a key from the Android Keystore, and the Keystore is neither backed up
     * nor transferred, so a restored copy cannot be decrypted and the prefs throw on the very first
     * access (i.e. when the API key is read in the LLM chain — the agent is dead right after a move).
     *
     * XML cannot reference a Kotlin constant, so `BackupRulesTest` catches a mismatch.
     */
    const val PREFS_NAME = "secure_settings"
  }
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class EncryptedSettingsRepository(
  private val context: Context,
) : SettingsRepository {

  private val masterKey: MasterKey by lazy {
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }

  private val prefs: SharedPreferences by lazy {
    EncryptedSharedPreferences.create(
      context,
      SettingsRepository.PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ).also { prefs ->
      // NVIDIA NIM was removed on Oct 25, 2026: nobody reads its key and provider choice any more, but on an
      // updated device they would just stay there — a live key the user no longer knows about.
      if (prefs.contains(OBSOLETE_NVIDIA_KEY) || prefs.contains(OBSOLETE_PROVIDER)) {
        prefs.edit().remove(OBSOLETE_NVIDIA_KEY).remove(OBSOLETE_PROVIDER).apply()
      }
    }
  }

  private companion object {
    const val OBSOLETE_NVIDIA_KEY = "api_key_NVIDIA"
    const val OBSOLETE_PROVIDER = "selected_provider"
  }

  override fun getApiKey(provider: String): String =
    prefs.getString("api_key_$provider", "").orEmpty()

  override fun setApiKey(provider: String, key: String) {
    prefs.edit().putString("api_key_$provider", key).apply()
  }

  override fun getSelectedModel(provider: String): String =
    prefs.getString(selectedModelKey(provider), "AUTO") ?: "AUTO"

  override fun setSelectedModel(model: String, provider: String) {
    prefs.edit().putString(selectedModelKey(provider), model).apply()
  }

  /**
   * OpenRouter keeps the old key: a choice made before the second route appeared is not lost.
   * Not `selected_provider` or anything with that name: such a key is deleted every time the prefs are
   * opened (a NIM legacy, see OBSOLETE_PROVIDER).
   */
  private fun selectedModelKey(provider: String): String =
    if (provider == "OPENROUTER") "selected_model" else "selected_model_$provider"
}
