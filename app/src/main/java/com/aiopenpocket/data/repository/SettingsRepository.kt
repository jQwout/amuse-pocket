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
 * Ключи провайдеров (OpenRouter и Meta Model API) и выбор модели.
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

  /**
   * Конкретная модель Meta на маршруте [provider] (`LlmProvider.name`). "AUTO" — идти по priority из
   * каталога.
   *
   * У каждого маршрута свой выбор: id одной и той же модели у OpenRouter и у прямого Meta Model API
   * разные («meta/muse-wonder» и «muse-wonder-1.0»). Общий выбор при вводе ключа Meta либо молча
   * терялся бы, либо показывал бы в Настройках модель, которой цепочка не знает.
   */
  fun getSelectedModel(provider: String = "OPENROUTER"): String

  fun setSelectedModel(model: String, provider: String = "OPENROUTER")

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

  override fun getSelectedModel(provider: String): String =
    prefs.getString(selectedModelKey(provider), "AUTO") ?: "AUTO"

  override fun setSelectedModel(model: String, provider: String) {
    prefs.edit().putString(selectedModelKey(provider), model).apply()
  }

  /**
   * OpenRouter — под прежним ключом: выбор, сделанный до появления второго маршрута, не теряется.
   * Не `selected_provider` и не что-то с этим именем: такой ключ удаляется при каждом открытии prefs
   * (наследство NIM, см. OBSOLETE_PROVIDER).
   */
  private fun selectedModelKey(provider: String): String =
    if (provider == "OPENROUTER") "selected_model" else "selected_model_$provider"
}
