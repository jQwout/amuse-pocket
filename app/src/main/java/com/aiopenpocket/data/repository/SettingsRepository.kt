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
 * Ключ OpenRouter и выбор модели.
 *
 * Интерфейс (реализация — [EncryptedSettingsRepository]) не ради «абстракции на будущее», а потому
 * что единственная реализация принципиально не создаётся в юнит-тесте: она поднимает Android
 * Keystore, а в Robolectric его нет — `KeyStoreException: AndroidKeyStore not found` прилетает при
 * первом же обращении к prefs. Пока это был финальный класс, весь SettingsViewModel не мог быть
 * покрыт ни одним тестом.
 */
interface SettingsRepository {

  fun getApiKey(provider: String): String

  fun setApiKey(provider: String, key: String)

  /** Конкретная модель Meta. "AUTO" — идти по priority из каталога. */
  fun getSelectedModel(): String

  fun setSelectedModel(model: String)

  companion object {
    /**
     * Имя файла EncryptedSharedPreferences. Константа, потому что оно дословно повторено в
     * `res/xml/backup_rules.xml` и `res/xml/data_extraction_rules.xml` — файл ОБЯЗАН быть исключён из
     * бэкапа: он зашифрован ключом из Android Keystore, а Keystore не бэкапится и не переносится, так
     * что восстановленную копию расшифровать нечем и prefs бросают при первом же обращении (то есть
     * при чтении API-ключа в LLM-цепочке — агент мёртв сразу после переезда).
     *
     * XML не умеет ссылаться на Kotlin-константу, поэтому расхождение ловит `BackupRulesTest`.
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
      // NVIDIA NIM выпилен 25 окт 2026: его ключ и выбор провайдера больше никто не читает, но на
      // обновлённом устройстве они так и лежали бы — живой ключ, о котором пользователь уже не знает.
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

  override fun getSelectedModel(): String =
    prefs.getString("selected_model", "AUTO") ?: "AUTO"

  override fun setSelectedModel(model: String) {
    prefs.edit().putString("selected_model", model).apply()
  }
}
