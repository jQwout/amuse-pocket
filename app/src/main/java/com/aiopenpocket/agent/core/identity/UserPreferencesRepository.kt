package com.aiopenpocket.agent.core.identity

// @trace app-settings-beb470

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "user_preferences",
)

/**
 * DataStore приходит снаружи (см. `UserPreferencesModule`), а не берётся из `Context` внутри.
 *
 * Делегат `preferencesDataStore` держит ОДИН экземпляр на процесс, привязанный к тому `Context`, с
 * которым к нему обратились первым. В проде это ровно то, что нужно. В JVM-тестах — общее состояние
 * на весь прогон: Robolectric выдаёт каждому тесту свой `filesDir`, а стор остаётся на пути и
 * в кеше ПЕРВОГО, так что тесты начинают видеть чужие записи и зависят от порядка запуска.
 *
 * Так это и проявилось: `UncompletedCoroutinesError` в `UserPreferencesChatFontTest` только в полном
 * прогоне, при зелёном прогоне того же класса в одиночку (тот же почерк, что у Room-Flow тестов,
 * посаженных на testDispatcher). Конкретный механизм зависания не воспроизводился прицельно —
 * поэтому здесь утверждается только проверяемое: параметр убирает общий на процесс канал между
 * тестами, каждый берёт свой файл.
 */
@SingleIn(AppScope::class)
@Inject
class UserPreferencesRepository(
  private val dataStore: DataStore<Preferences>,
) {
  val onboardingCompleted: Flow<Boolean> =
    dataStore.data.map { prefs ->
      prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

  val defaultPersonaId: Flow<String> =
    dataStore.data.map { prefs ->
      prefs[KEY_DEFAULT_PERSONA_ID] ?: DEFAULT_PERSONA_ID
    }

  val hasSeenTutorial: Flow<Boolean> =
    dataStore.data.map { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] ?: false
    }

  val roadmapProgress: Flow<Set<String>> =
    dataStore.data.map { prefs ->
      prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

  suspend fun getDefaultPersonaId(): String =
    dataStore.data
      .map { prefs -> prefs[KEY_DEFAULT_PERSONA_ID] ?: DEFAULT_PERSONA_ID }
      .first()

  val language: Flow<String> =
    dataStore.data.map { prefs ->
      prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

  suspend fun getLanguage(): String =
    dataStore.data
      .map { prefs -> prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }
      .first()

  /** Лимит витков гоал-мода (петля ReAct до достижения цели). По умолчанию 3. */
  val goalModeMaxIterations: Flow<Int> =
    dataStore.data.map { prefs ->
      prefs[KEY_GOAL_MODE_MAX_ITERATIONS] ?: DEFAULT_GOAL_MODE_MAX_ITERATIONS
    }

  suspend fun getGoalModeMaxIterations(): Int =
    dataStore.data
      .map { prefs -> prefs[KEY_GOAL_MODE_MAX_ITERATIONS] ?: DEFAULT_GOAL_MODE_MAX_ITERATIONS }
      .first()

  suspend fun setGoalModeMaxIterations(value: Int) {
    dataStore.edit { prefs ->
      prefs[KEY_GOAL_MODE_MAX_ITERATIONS] = value.coerceIn(1, GOAL_MODE_MAX_ITERATIONS_CAP)
    }
  }

  /** Пресет размера шрифта сообщений в чате. Хранится по имени enum; неизвестное значение → MEDIUM. */
  val chatFontScale: Flow<ChatFontScale> =
    dataStore.data.map { prefs ->
      ChatFontScale.fromName(prefs[KEY_CHAT_FONT_SCALE])
    }

  suspend fun getChatFontScale(): ChatFontScale = chatFontScale.first()

  suspend fun setChatFontScale(value: ChatFontScale) {
    dataStore.edit { prefs ->
      prefs[KEY_CHAT_FONT_SCALE] = value.name
    }
  }

  suspend fun setLanguage(code: String) {
    dataStore.edit { prefs ->
      prefs[KEY_LANGUAGE] = code
    }
  }

  suspend fun getHasSeenTutorial(): Boolean =
    dataStore.data
      .map { prefs -> prefs[KEY_HAS_SEEN_TUTORIAL] ?: false }
      .first()

  suspend fun getRoadmapProgress(): Set<String> =
    dataStore.data
      .map { prefs -> prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet() }
      .first()

  suspend fun setDefaultPersonaId(personaId: String) {
    dataStore.edit { prefs ->
      prefs[KEY_DEFAULT_PERSONA_ID] = personaId
    }
  }

  suspend fun setHasSeenTutorial(seen: Boolean) {
    dataStore.edit { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] = seen
    }
  }

  suspend fun completeOnboarding(personaId: String, language: String = DEFAULT_LANGUAGE) {
    dataStore.edit { prefs ->
      prefs[KEY_ONBOARDING_COMPLETED] = true
      prefs[KEY_DEFAULT_PERSONA_ID] = personaId
      prefs[KEY_LANGUAGE] = language
      // Онбординг = урок 1: отмечаем его пройденным, чтобы на карте открылся урок 2.
      val progress = prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
      prefs[KEY_ROADMAP_PROGRESS] = (progress + ONBOARDING_LEVEL_ID).joinToString(",")
    }
  }

  suspend fun completeTutorial() {
    dataStore.edit { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] = true
    }
  }

  suspend fun completeRoadmapLevel(levelId: String) {
    dataStore.edit { prefs ->
      val current = prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
      prefs[KEY_ROADMAP_PROGRESS] = (current + levelId).joinToString(",")
    }
  }

  companion object {
    const val DEFAULT_PERSONA_ID = "sharp_partner"
    const val DEFAULT_LANGUAGE = "ru"
    const val DEFAULT_GOAL_MODE_MAX_ITERATIONS = 3
    const val GOAL_MODE_MAX_ITERATIONS_CAP = 10

    private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val KEY_DEFAULT_PERSONA_ID = stringPreferencesKey("default_persona_id")
    private val KEY_HAS_SEEN_TUTORIAL = booleanPreferencesKey("has_seen_tutorial")
    private val KEY_ROADMAP_PROGRESS = stringPreferencesKey("roadmap_progress")
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val KEY_GOAL_MODE_MAX_ITERATIONS = intPreferencesKey("goal_mode_max_iterations")
    private val KEY_CHAT_FONT_SCALE = stringPreferencesKey("chat_font_scale")

    /** Roadmap level id of the onboarding lesson; completed automatically after onboarding. */
    const val ONBOARDING_LEVEL_ID = "l1_welcome"
  }
}
