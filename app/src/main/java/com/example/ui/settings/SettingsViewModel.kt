package com.aiopenpocket.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.persona.PersonaCatalog
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
          personas = PersonaCatalog.all.map { it.id },
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

  fun save() {
    val current = _state.value
    settingsRepository.setApiKey("NVIDIA", current.nvidiaKey)
    settingsRepository.setApiKey("GROQ", current.groqKey)
    settingsRepository.setApiKey("GITHUB", current.githubKey)
    settingsRepository.setSelectedProvider(current.selectedProvider)
    viewModelScope.launch {
      userPreferencesRepository.setDefaultPersonaId(current.personaId)
    }
    _state.update { it.copy(saved = true) }
  }
}
