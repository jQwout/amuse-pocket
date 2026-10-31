package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.ui.settings.weeklySummary
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Profile screen: the person's own data (the "about me" from onboarding, editable in place), what the
 * assistant remembers about them, and the week's spend.
 *
 * Reads happen once per opening of the screen — each navigation creates a fresh ViewModel — so there is no
 * observer on the facts table: nothing can change them while this screen is in front, except the person.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ProfileViewModel(
  private val memoryRepository: MemoryRepository,
  private val llmUsageDao: LlmUsageDao,
) : ViewModel() {

  private val _state = MutableStateFlow(ProfileUiState())
  val state: StateFlow<ProfileUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val profile = memoryRepository.getUserProfile()
      val sections = groupUserFacts(memoryRepository.getSharedFacts())
      _state.update {
        it.copy(
          loaded = true,
          draft = profile,
          persisted = profile.trimmed(),
          showReligion = profile.religion.isNotBlank(),
          factSections = sections,
        )
      }
    }
    // Spend arrives in its own coroutine: the sums must not hold up the fields the person came for.
    viewModelScope.launch {
      val usage = llmUsageDao.weeklySummary()
      if (usage != null) _state.update { it.copy(usage = usage) }
    }
  }

  fun onFieldChange(field: ProfileField, value: String) {
    _state.update { it.copy(draft = it.draft.with(field, value), justSaved = false) }
  }

  /** Writes the fields trimmed, through the same path onboarding uses ([MemoryRepository.saveUserProfile]). */
  fun save() {
    val toSave = _state.value.draft.trimmed()
    viewModelScope.launch {
      memoryRepository.saveUserProfile(toSave)
      _state.update { it.copy(draft = toSave, persisted = toSave, justSaved = true) }
    }
  }

  /** First step of forgetting: only marks the fact. Nothing is deleted until [confirmDelete]. */
  fun requestDelete(fact: Fact) {
    _state.update { it.copy(pendingDelete = fact) }
  }

  fun cancelDelete() {
    _state.update { it.copy(pendingDelete = null) }
  }

  fun confirmDelete() {
    val fact = _state.value.pendingDelete ?: return
    viewModelScope.launch {
      memoryRepository.deleteFact(fact.id)
      val sections = groupUserFacts(memoryRepository.getSharedFacts())
      _state.update { it.copy(pendingDelete = null, factSections = sections) }
    }
  }
}
