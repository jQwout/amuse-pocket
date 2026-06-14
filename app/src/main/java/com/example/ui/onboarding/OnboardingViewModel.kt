package com.aiopenpocket.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(OnboardingViewModel::class)
class OnboardingViewModel(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val memoryRepository: MemoryRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(OnboardingUiState())
  val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

  private var introJob: Job? = null

  fun handleIntent(intent: OnboardingIntent) {
    when (intent) {
      OnboardingIntent.StartIntroStreaming -> startIntroStreamingIfNeeded()
      OnboardingIntent.NextStep -> goToNextStep()
      OnboardingIntent.PreviousStep -> goToPreviousStep()
      is OnboardingIntent.SelectPersona -> {
        _state.update { it.copy(selectedPersonaId = intent.personaId) }
      }
      is OnboardingIntent.UpdateName -> _state.update { it.copy(name = intent.value) }
      is OnboardingIntent.UpdateAge -> _state.update { it.copy(age = intent.value) }
      is OnboardingIntent.UpdateGender -> _state.update { it.copy(gender = intent.value) }
      is OnboardingIntent.UpdatePreferredAddress -> {
        _state.update { it.copy(preferredAddress = intent.value) }
      }
      is OnboardingIntent.UpdateExtraInfo -> _state.update { it.copy(extraInfo = intent.value) }
      OnboardingIntent.Complete -> completeOnboarding()
    }
  }

  private fun startIntroStreamingIfNeeded() {
    val current = _state.value
    if (current.introAlreadyPlayed) {
      _state.update {
        it.copy(
          introStreamingText = OnboardingContent.INTRO_MARKDOWN.trim(),
          isIntroStreaming = false,
          isIntroComplete = true,
        )
      }
      return
    }

    introJob?.cancel()
    introJob = viewModelScope.launch {
      val fullText = OnboardingContent.INTRO_MARKDOWN.trim()
      _state.update {
        it.copy(
          introStreamingText = "",
          isIntroStreaming = true,
          isIntroComplete = false,
        )
      }
      fullText.chunked(2).forEach { chunk ->
        delay(STREAM_CHAR_DELAY_MS)
        _state.update { state ->
          state.copy(introStreamingText = state.introStreamingText + chunk)
        }
      }
      _state.update {
        it.copy(
          isIntroStreaming = false,
          isIntroComplete = true,
          introAlreadyPlayed = true,
        )
      }
    }
  }

  private fun goToNextStep() {
    val current = _state.value
    if (!current.canProceed) return
    when (current.currentStep) {
      OnboardingStep.INTRO -> _state.update { it.copy(currentStep = OnboardingStep.PERSONA) }
      OnboardingStep.PERSONA -> _state.update { it.copy(currentStep = OnboardingStep.PROFILE) }
      OnboardingStep.PROFILE -> completeOnboarding()
    }
  }

  private fun goToPreviousStep() {
    when (_state.value.currentStep) {
      OnboardingStep.PERSONA -> _state.update { it.copy(currentStep = OnboardingStep.INTRO) }
      OnboardingStep.PROFILE -> _state.update { it.copy(currentStep = OnboardingStep.PERSONA) }
      OnboardingStep.INTRO -> Unit
    }
  }

  private fun completeOnboarding() {
    val current = _state.value
    if (!current.canProceed || current.isSaving) return

    viewModelScope.launch {
      _state.update { it.copy(isSaving = true, error = null) }
      try {
        memoryRepository.saveUserProfile(
          UserProfile(
            name = current.name,
            preferredAddress = current.preferredAddress,
            age = current.age,
            gender = current.gender,
            extraInfo = current.extraInfo,
          ),
        )
        // Навигацию выполняет наблюдатель флага onboardingCompleted в MainActivity —
        // единый источник истины, чтобы не было двойной/гоночной навигации.
        userPreferencesRepository.completeOnboarding(current.selectedPersonaId)
      } catch (e: Exception) {
        _state.update {
          it.copy(
            isSaving = false,
            error = e.message ?: "Не удалось сохранить данные",
          )
        }
      }
    }
  }

  companion object {
    private const val STREAM_CHAR_DELAY_MS = 25L
  }
}
