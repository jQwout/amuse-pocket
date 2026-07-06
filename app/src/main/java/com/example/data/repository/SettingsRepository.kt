package com.aiopenpocket.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
class SettingsRepository @Inject constructor(
  private val context: Context,
) {

  private val masterKey: MasterKey by lazy {
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }

  private val prefs: SharedPreferences by lazy {
    EncryptedSharedPreferences.create(
      context,
      "secure_settings",
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  fun getApiKey(provider: String): String =
    prefs.getString("api_key_$provider", "").orEmpty()

  fun setApiKey(provider: String, key: String) {
    prefs.edit().putString("api_key_$provider", key).apply()
  }

  fun getSelectedProvider(): String =
    prefs.getString("selected_provider", "AUTO") ?: "AUTO"

  fun setSelectedProvider(provider: String) {
    prefs.edit().putString("selected_provider", provider).apply()
  }
}
