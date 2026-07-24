package com.aiopenpocket.data.repository

// @trace app-settings-beb470

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
@Inject
class SettingsRepository(
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
      PREFS_NAME,
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

  /** Конкретная NIM-модель, выбранная вручную в Настройках. "AUTO" — по приоритету из models.json. */
  fun getSelectedModel(): String =
    prefs.getString("selected_model", "AUTO") ?: "AUTO"

  fun setSelectedModel(model: String) {
    prefs.edit().putString("selected_model", model).apply()
  }

  companion object {
    /**
     * Имя файла EncryptedSharedPreferences. Константа, потому что оно дословно повторено в
     * `res/xml/backup_rules.xml` и `res/xml/data_extraction_rules.xml` — файл ОБЯЗАН быть исключён из
     * бэкапа: он зашифрован ключом из Android Keystore, а Keystore не бэкапится и не переносится, так
     * что восстановленную копию расшифровать нечем и [prefs] бросает при первом же обращении (то есть
     * при чтении API-ключа в LLM-цепочке — агент мёртв сразу после переезда).
     *
     * XML не умеет ссылаться на Kotlin-константу, поэтому расхождение ловит `BackupRulesTest`.
     */
    const val PREFS_NAME = "secure_settings"
  }
}
