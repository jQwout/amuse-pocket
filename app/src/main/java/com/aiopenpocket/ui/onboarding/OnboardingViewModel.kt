package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.profile.ExtractUserProfileUseCase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class OnboardingViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val memoryRepository: MemoryRepository,
    private val extractUserProfile: ExtractUserProfileUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var introJob: Job? = null
    private var extractionJob: Job? = null

    fun handleIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.StartIntroStreaming -> startIntroStreamingIfNeeded()
            OnboardingIntent.NextStep -> goToNextStep()
            OnboardingIntent.PreviousStep -> goToPreviousStep()
            is OnboardingIntent.SelectLanguage -> {
                _state.update { it.copy(selectedLanguage = intent.code) }
            }

            // Мастер персону больше не спрашивает (см. OnboardingStep), но интент жив: его шлёт
            // PersonaSelectionStep, если экран вернут, и через него же приезжает выбор из Настроек.
            is OnboardingIntent.SelectPersona -> {
                _state.update { it.copy(selectedPersonaId = intent.personaId) }
            }

            is OnboardingIntent.UpdateAboutMe -> {
                _state.update { it.copy(aboutMeText = intent.value, extractionError = null) }
            }

            OnboardingIntent.Complete -> completeOnboarding()
        }
    }

    fun loadExistingProfile() {
        viewModelScope.launch {
            val personaId = userPreferencesRepository.getDefaultPersonaId()
            val language = userPreferencesRepository.getLanguage()
            val facts = memoryRepository.getFactsByCategory("profile").associate { it.key to it.value }
            val stored = UserProfile(
                name = facts["user.name"].orEmpty(),
                preferredAddress = facts["user.preferred_address"].orEmpty(),
                age = facts["user.age"].orEmpty(),
                gender = facts["user.gender"].orEmpty(),
                religion = facts["user.religion"].orEmpty(),
                extraInfo = facts["user.extra_info"].orEmpty(),
            )
            _state.update {
                it.mergeProfile(stored).copy(
                    selectedPersonaId = personaId,
                    selectedLanguage = language,
                )
            }
        }
    }

    /** Keep existing field values when [other] has them blank — used by both load and extract. */
    private fun OnboardingUiState.mergeProfile(other: UserProfile): OnboardingUiState = copy(
        name = other.name.ifBlank { name },
        preferredAddress = other.preferredAddress.ifBlank { preferredAddress },
        age = other.age.ifBlank { age },
        gender = other.gender.ifBlank { gender },
        religion = other.religion.ifBlank { religion },
        extraInfo = other.extraInfo.ifBlank { extraInfo },
    )

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

    private fun goToStep(step: OnboardingStep) {
        _state.update { it.copy(currentStep = step) }
    }

    private fun goToNextStep() {
        val current = _state.value
        if (!current.canProceed) return
        when (current.currentStep) {
            OnboardingStep.LANGUAGE -> goToStep(OnboardingStep.INTRO)
            OnboardingStep.INTRO -> goToStep(OnboardingStep.PROFILE)
            // PROFILE — последний шаг: разбираем рассказ о себе и на этом настройку завершаем.
            OnboardingStep.PROFILE -> extractProfileAndFinish()
        }
    }

    private fun goToPreviousStep() {
        when (_state.value.currentStep) {
            OnboardingStep.INTRO -> goToStep(OnboardingStep.LANGUAGE)
            OnboardingStep.PROFILE -> goToStep(OnboardingStep.INTRO)
            OnboardingStep.LANGUAGE -> Unit
        }
    }

    /**
     * Разбирает рассказ о себе и сразу завершает настройку: PROFILE — последний шаг мастера.
     * Профиль сохраняется дважды подряд (здесь и в [completeOnboarding]) — так же, как до
     * сокращения мастера: второй раз пишутся уже слитые поля, включая то, что человек ввёл ранее.
     */
    private fun extractProfileAndFinish() {
        if (extractionJob?.isActive == true) return
        val current = _state.value
        if (current.aboutMeText.isBlank()) return

        extractionJob = viewModelScope.launch {
            _state.update { it.copy(isExtracting = true, extractionError = null) }
            try {
                val provided = extractUserProfile(current.aboutMeText)
                    ?: throw IllegalStateException("Не удалось разобрать ответ модели")
                memoryRepository.saveUserProfile(provided)

                _state.update { it.mergeProfile(provided).copy(isExtracting = false) }
                completeOnboarding()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Экран покинут (навигация/пересоздание ViewModel) — не глотаем отмену, иначе она
                // тратится на обновление state уже уничтожаемой VM вместо нормального распространения.
                throw e
            } catch (e: Exception) {
                // Без модели настройку не держим: нет ключа (магазинная сборка его не вшивает), нет
                // сети или провайдер упал — а ввести ключ можно только в Настройках, которые открываются
                // ПОСЛЕ онбординга. Раньше здесь был тупик: ошибка и та же кнопка «Готово» навсегда.
                // Рассказ о себе сохраняем как есть — разбор по полям ничего не добавляет к тому, что
                // модель и так прочтёт в «о себе», а имя агент узнает в разговоре.
                android.util.Log.w("OnboardingViewModel", "profile extraction failed, saving raw text: ${e.message}")
                _state.update {
                    it.copy(isExtracting = false, extraInfo = it.extraInfo.ifBlank { current.aboutMeText.trim() })
                }
                completeOnboarding()
            }
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
                        religion = current.religion,
                        extraInfo = current.extraInfo,
                    ),
                )
                userPreferencesRepository.completeOnboarding(current.selectedPersonaId, current.selectedLanguage)
                _state.update { it.copy(isSaving = false, isCompleted = true) }
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
