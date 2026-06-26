package com.aiopenpocket.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(SettingsViewModel::class)
class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      _state.update {
        it.copy(
          nvidiaKey = settingsRepository.getApiKey("NVIDIA"),
          groqKey = settingsRepository.getApiKey("GROQ"),
          githubKey = settingsRepository.getApiKey("GITHUB"),
          selectedProvider = settingsRepository.getSelectedProvider(),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          personas = listOf(
            "sharp_partner",
            "attentive_secretary",
            "egalitarian_partner",
            "friendly_companion",
            "ironic_assistant",
            "silent_butler",
            "strict_coach",
          ),
        )
      }
    }
  }

  fun updateNvidiaKey(key: String) {
    _state.update { it.copy(nvidiaKey = key, saved = false) }
  }

  fun updateGroqKey(key: String) {
    _state.update { it.copy(groqKey = key, saved = false) }
  }

  fun updateGithubKey(key: String) {
    _state.update { it.copy(githubKey = key, saved = false) }
  }

  fun updateProvider(provider: String) {
    _state.update { it.copy(selectedProvider = provider, saved = false) }
  }

  fun updatePersona(personaId: String) {
    _state.update { it.copy(personaId = personaId, saved = false) }
  }

  fun updateGoalMaxIterations(value: Int) {
    _state.update { it.copy(goalMaxIterations = value.coerceIn(1, 10), saved = false) }
  }

  fun save() {
    val current = _state.value
    settingsRepository.setApiKey("NVIDIA", current.nvidiaKey)
    settingsRepository.setApiKey("GROQ", current.groqKey)
    settingsRepository.setApiKey("GITHUB", current.githubKey)
    settingsRepository.setSelectedProvider(current.selectedProvider)
    viewModelScope.launch {
      // «Сохранено» показываем только после реальной записи в DataStore (suspend), а не до неё.
      userPreferencesRepository.setDefaultPersonaId(current.personaId)
      userPreferencesRepository.setGoalModeMaxIterations(current.goalMaxIterations)
      // ...и только если пользователь не успел изменить поля во время записи (иначе соврём «Сохранено»).
      _state.update { s ->
        val unchanged = s.nvidiaKey == current.nvidiaKey && s.groqKey == current.groqKey &&
          s.githubKey == current.githubKey && s.selectedProvider == current.selectedProvider &&
          s.personaId == current.personaId && s.goalMaxIterations == current.goalMaxIterations
        if (unchanged) s.copy(saved = true) else s
      }
    }
  }
}
