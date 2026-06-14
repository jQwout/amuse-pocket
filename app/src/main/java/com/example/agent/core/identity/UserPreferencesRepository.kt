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

  suspend fun completeOnboarding(personaId: String) {
    context.userPreferencesDataStore.edit { prefs ->
      prefs[KEY_ONBOARDING_COMPLETED] = true
      prefs[KEY_DEFAULT_PERSONA_ID] = personaId
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

    private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val KEY_DEFAULT_PERSONA_ID = stringPreferencesKey("default_persona_id")
    private val KEY_HAS_SEEN_TUTORIAL = booleanPreferencesKey("has_seen_tutorial")
    private val KEY_ROADMAP_PROGRESS = stringPreferencesKey("roadmap_progress")
  }
}
