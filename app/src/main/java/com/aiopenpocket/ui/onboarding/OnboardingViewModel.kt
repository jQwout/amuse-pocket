package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.profile.ExtractUserProfileUseCase
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class OnboardingViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val memoryRepository: MemoryRepository,
    private val extractUserProfile: ExtractUserProfileUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // The initial choice is the current language, not "ru" by default: the language is written to the settings
    // right when chosen, and after a restart in the middle of the wizard the screen is already English — while
    // "Done" used to write back Russian from the default state.
    private val _state = MutableStateFlow(OnboardingUiState(selectedLanguage = AppLanguage.code))
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
                // Right away, not at the end of the wizard: the rest of the onboarding steps must already be in this language.
                viewModelScope.launch { userPreferencesRepository.setLanguage(intent.code) }
            }

            // The wizard no longer asks for the persona (see OnboardingStep), but the intent lives on: PersonaSelectionStep
            // sends it if the screen comes back, and the choice from Settings arrives through it too.
            is OnboardingIntent.SelectPersona -> {
                _state.update { it.copy(selectedPersonaId = intent.personaId) }
            }

            is OnboardingIntent.UpdateAboutMe -> {
                _state.update { it.copy(aboutMeText = intent.value, extractionError = null) }
            }

            is OnboardingIntent.UpdateMetaKey -> _state.update { it.copy(metaKeyInput = intent.value, error = null) }

            OnboardingIntent.SkipMetaKey -> if (_state.value.currentStep == OnboardingStep.KEY) goToStep(OnboardingStep.PROFILE)

            OnboardingIntent.Complete -> completeOnboarding()
        }
    }

    fun loadExistingProfile() {
        viewModelScope.launch {
            val personaId = userPreferencesRepository.getDefaultPersonaId()
            val language = userPreferencesRepository.getLanguage()
            // So re-entering the wizard from the roadmap shows the key that is already saved. IO: the first
            // access to EncryptedSharedPreferences brings up the Keystore.
            val savedKey = withContext(Dispatchers.IO) { settingsRepository.getApiKey(META_KEY_NAME) }
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
                    // Typed-in text wins over the stored key: a late disk read must not wipe what is being typed.
                    metaKeyInput = it.metaKeyInput.ifBlank { savedKey },
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
                    introStreamingText = OnboardingContent.INTRO_TEXT.trim(),
                    isIntroStreaming = false,
                    isIntroComplete = true,
                )
            }
            return
        }

        introJob?.cancel()
        introJob = viewModelScope.launch {
            val fullText = OnboardingContent.INTRO_TEXT.trim()
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
            OnboardingStep.INTRO -> goToStep(OnboardingStep.KEY)
            OnboardingStep.KEY -> saveMetaKeyAndContinue()
            // PROFILE is the last step: parse the story about oneself and finish the setup on that.
            OnboardingStep.PROFILE -> extractProfileAndFinish()
        }
    }

    private fun goToPreviousStep() {
        when (_state.value.currentStep) {
            OnboardingStep.INTRO -> goToStep(OnboardingStep.LANGUAGE)
            OnboardingStep.KEY -> goToStep(OnboardingStep.INTRO)
            OnboardingStep.PROFILE -> goToStep(OnboardingStep.KEY)
            OnboardingStep.LANGUAGE -> Unit
        }
    }

    /**
     * Saves the typed Meta key and moves on to the profile step. The key is stored EXACTLY the way Settings
     * stores it (`setApiKey("META", key.trim())` in EncryptedSharedPreferences): the trim matters because a
     * key is pasted from the clipboard, and a trailing newline in the Authorization header looks like
     * "the key is right, but Meta answers 401". The step moves on only AFTER the write, so the profile
     * parsing right after it already finds the key.
     */
    private fun saveMetaKeyAndContinue() {
        val key = _state.value.metaKeyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { settingsRepository.setApiKey(META_KEY_NAME, key) }
                goToStep(OnboardingStep.PROFILE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The Keystore can be unavailable on odd devices. Stay on the step and say so; "Skip" still works.
                android.util.Log.w("OnboardingViewModel", "could not save the Meta key: ${e.message}")
                _state.update { it.copy(error = tr("Не удалось сохранить ключ — попробуй ещё раз или пропусти шаг.", "Couldn't save the key — try again or skip this step.")) }
            }
        }
    }

    /**
     * Parses the story about oneself and finishes the setup right away: PROFILE is the wizard's last step.
     * The profile is saved twice in a row (here and in [completeOnboarding]) — the same as before the wizard
     * was shortened: the second time the already merged fields are written, including what the person entered
     * earlier.
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
                // The screen was left (navigation/the ViewModel recreated) — cancellation is not swallowed, otherwise it would
                // be spent on updating the state of an already dying VM instead of propagating normally.
                throw e
            } catch (e: Exception) {
                // The setup is not held hostage to the model: the key step can be skipped (no key is bundled into
                // any build), or there is no network, or the provider is down. This used to be a dead end: an
                // error and the same "Done" button forever. The story about oneself is
                // saved as is — parsing it into fields adds nothing to what the model reads in "about me" anyway,
                // and the agent learns the name in conversation.
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
                        error = e.message ?: tr("Не удалось сохранить данные", "Couldn't save your data"),
                    )
                }
            }
        }
    }

    companion object {
        private const val STREAM_CHAR_DELAY_MS = 25L

        /** The name SettingsRepository files the Meta key under (the same one Settings and LlmModule use). */
        private const val META_KEY_NAME = "META"
    }
}
