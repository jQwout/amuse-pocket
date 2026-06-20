package com.aiopenpocket.agent.core.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "user_preferences",
)

@SingleIn(AppScope::class)
class UserPreferencesRepository @Inject constructor(
  private val context: Context,
) {
  val onboardingCompleted: Flow<Boolean> =
    context.userPreferencesDataStore.data.map { prefs ->
      prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

  val defaultPersonaId: Flow<String> =
    context.userPreferencesDataStore.data.map { prefs ->
      prefs[KEY_DEFAULT_PERSONA_ID] ?: DEFAULT_PERSONA_ID
    }

  val hasSeenTutorial: Flow<Boolean> =
    context.userPreferencesDataStore.data.map { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] ?: false
    }

  val roadmapProgress: Flow<Set<String>> =
    context.userPreferencesDataStore.data.map { prefs ->
      prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

  suspend fun getDefaultPersonaId(): String =
    context.userPreferencesDataStore.data
      .map { prefs -> prefs[KEY_DEFAULT_PERSONA_ID] ?: DEFAULT_PERSONA_ID }
      .first()

  val language: Flow<String> =
    context.userPreferencesDataStore.data.map { prefs ->
      prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

  suspend fun getLanguage(): String =
    context.userPreferencesDataStore.data
      .map { prefs -> prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }
      .first()

  suspend fun setLanguage(code: String) {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_LANGUAGE] = code
    }
  }

  suspend fun getHasSeenTutorial(): Boolean =
    context.userPreferencesDataStore.data
      .map { prefs -> prefs[KEY_HAS_SEEN_TUTORIAL] ?: false }
      .first()

  suspend fun getRoadmapProgress(): Set<String> =
    context.userPreferencesDataStore.data
      .map { prefs -> prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet() }
      .first()

  suspend fun setDefaultPersonaId(personaId: String) {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_DEFAULT_PERSONA_ID] = personaId
    }
  }

  suspend fun setHasSeenTutorial(seen: Boolean) {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] = seen
    }
  }

  suspend fun completeOnboarding(personaId: String, language: String = DEFAULT_LANGUAGE) {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_ONBOARDING_COMPLETED] = true
      prefs[KEY_DEFAULT_PERSONA_ID] = personaId
      prefs[KEY_LANGUAGE] = language
      // Онбординг = урок 1: отмечаем его пройденным, чтобы на карте открылся урок 2.
      val progress = prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
      prefs[KEY_ROADMAP_PROGRESS] = (progress + ONBOARDING_LEVEL_ID).joinToString(",")
    }
  }

  suspend fun completeTutorial() {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_HAS_SEEN_TUTORIAL] = true
    }
  }

  suspend fun completeRoadmapLevel(levelId: String) {
    context.userPreferencesDataStore.edit { prefs ->
      val current = prefs[KEY_ROADMAP_PROGRESS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
      prefs[KEY_ROADMAP_PROGRESS] = (current + levelId).joinToString(",")
    }
  }

  companion object {
    const val DEFAULT_PERSONA_ID = "sharp_partner"
    const val DEFAULT_LANGUAGE = "ru"

    private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val KEY_DEFAULT_PERSONA_ID = stringPreferencesKey("default_persona_id")
    private val KEY_HAS_SEEN_TUTORIAL = booleanPreferencesKey("has_seen_tutorial")
    private val KEY_ROADMAP_PROGRESS = stringPreferencesKey("roadmap_progress")
    private val KEY_LANGUAGE = stringPreferencesKey("language")

    /** Roadmap level id of the onboarding lesson; completed automatically after onboarding. */
    const val ONBOARDING_LEVEL_ID = "l1_welcome"
  }
}
