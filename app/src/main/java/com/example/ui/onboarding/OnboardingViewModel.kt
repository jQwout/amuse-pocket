package com.aiopenpocket.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
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
  private val lessonRepository: LessonRepository,
  private val llmClient: LlmClient,
  private val settingsRepository: SettingsRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(OnboardingUiState())
  val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

  private var askJob: Job? = null

  init {
    viewModelScope.launch {
      val lesson = lessonRepository.getLesson(LESSON_ID)
      _state.update { it.copy(steps = lesson.steps) }
    }
  }

  fun handleIntent(intent: OnboardingIntent) {
    when (intent) {
      OnboardingIntent.NextStep -> goToNextStep()
      OnboardingIntent.PreviousStep -> goToPreviousStep()
      OnboardingIntent.SkipStep -> goToNextStep()
      OnboardingIntent.OpenAsk -> _state.update { it.copy(askSheetOpen = true, askError = null) }
      OnboardingIntent.CloseAsk -> { askJob?.cancel(); _state.update { it.copy(askSheetOpen = false, isAsking = false) } }
      OnboardingIntent.CancelAsk -> { askJob?.cancel(); _state.update { it.copy(isAsking = false) } }
      is OnboardingIntent.SendAsk -> sendAsk(intent.text)
      is OnboardingIntent.SelectLanguage -> _state.update { it.copy(selectedLanguage = intent.code) }
      is OnboardingIntent.SelectPersona -> _state.update { it.copy(selectedPersonaId = intent.personaId) }
      is OnboardingIntent.UpdateName -> _state.update { it.copy(name = intent.value) }
      is OnboardingIntent.UpdateAge -> _state.update { it.copy(age = intent.value) }
      is OnboardingIntent.UpdateGender -> _state.update { it.copy(gender = intent.value) }
      is OnboardingIntent.UpdateReligion -> _state.update { it.copy(religion = intent.value) }
      is OnboardingIntent.UpdatePreferredAddress -> _state.update { it.copy(preferredAddress = intent.value) }
      is OnboardingIntent.UpdateExtraInfo -> _state.update { it.copy(extraInfo = intent.value) }
    }
  }

  /**
   * Режим правки (вход с карты, урок 1): подтягиваем уже сохранённые персону, язык и профиль,
   * чтобы завершение не затёрло их пустыми дефолтами. На первом запуске не вызывается.
   */
  fun loadExistingProfile() {
    viewModelScope.launch {
      val personaId = userPreferencesRepository.getDefaultPersonaId()
      val language = userPreferencesRepository.getLanguage()
      val facts = memoryRepository.getFactsByCategory("profile").associate { it.key to it.value }
      _state.update {
        it.copy(
          selectedPersonaId = personaId,
          selectedLanguage = language,
          name = facts["user.name"] ?: it.name,
          preferredAddress = facts["user.preferred_address"] ?: it.preferredAddress,
          age = facts["user.age"] ?: it.age,
          gender = facts["user.gender"] ?: it.gender,
          religion = facts["user.religion"] ?: it.religion,
          extraInfo = facts["user.extra_info"] ?: it.extraInfo,
        )
      }
    }
  }

  private fun goToNextStep() {
    val current = _state.value
    if (!current.canProceed) return
    if (current.isLastStep) {
      completeOnboarding()
      return
    }
    askJob?.cancel()
    _state.update {
      it.copy(currentStepIndex = it.currentStepIndex + 1, error = null).clearedAsk()
    }
  }

  private fun goToPreviousStep() {
    askJob?.cancel()
    _state.update {
      if (it.currentStepIndex > 0) {
        it.copy(currentStepIndex = it.currentStepIndex - 1, error = null).clearedAsk()
      } else {
        it
      }
    }
  }

  /** Reset the ephemeral "Спросить" thread when the step changes. */
  private fun OnboardingUiState.clearedAsk() =
    copy(askSheetOpen = false, askThread = emptyList(), isAsking = false, askError = null)

  /** Ask the LLM a free-form question about the current step (no history persisted, no tools). */
  private fun sendAsk(text: String) {
    val question = text.trim()
    if (question.isEmpty() || _state.value.isAsking) return
    val step = _state.value.currentStep ?: return
    if (step.type != "text") return
    askJob?.cancel()
    askJob = viewModelScope.launch {
      _state.update {
        it.copy(
          askThread = it.askThread + AskMessage("user", question) + AskMessage("assistant", ""),
          isAsking = true,
          askError = null,
        )
      }
      // Временно гоним всё на NIM; выбор провайдера выпилим позже.
      val provider = LlmProvider.NIM
      val system = ChatMessage(
        role = "system",
        content = "Ты — дружелюбный наставник в обучающем приложении. Идёт урок «Анатомия чата». " +
          "Текущий шаг объясняет пользователю: \"${step.text}\". Отвечай на вопросы именно про этот шаг, " +
          "простыми словами и коротко. Не раскрывай следующие шаги урока.",
      )
      // Берём тред без пустого assistant-плейсхолдера в конце.
      val history = _state.value.askThread.dropLast(1).map { ChatMessage(it.role, it.content) }
      try {
        llmClient.streamChat(listOf(system) + history, emptyList(), provider).collect { event ->
          when (event) {
            is StreamEvent.TextDelta -> _state.update { it.appendToLastAssistant(event.content) }
            is StreamEvent.Error -> _state.update { it.copy(isAsking = false, askError = event.message) }
            is StreamEvent.Finish -> _state.update { it.copy(isAsking = false) }
            else -> Unit
          }
        }
        _state.update { it.copy(isAsking = false) }
      } catch (e: Exception) {
        _state.update { it.copy(isAsking = false, askError = e.message ?: "Не удалось получить ответ") }
      }
    }
  }

  private fun OnboardingUiState.appendToLastAssistant(delta: String): OnboardingUiState {
    if (askThread.isEmpty()) return this
    val updated = askThread.toMutableList()
    val last = updated.lastIndex
    updated[last] = updated[last].copy(content = updated[last].content + delta)
    return copy(askThread = updated)
  }

  private fun completeOnboarding() {
    val current = _state.value
    if (current.isSaving) return

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
        userPreferencesRepository.completeRoadmapLevel(LESSON_ID)
        // Навигацию первого запуска выполняет наблюдатель флага onboardingCompleted в
        // MainActivity. Для режима правки экран реагирует на isCompleted.
        _state.update { it.copy(isSaving = false, isCompleted = true) }
      } catch (e: Exception) {
        _state.update {
          it.copy(isSaving = false, error = e.message ?: "Не удалось сохранить данные")
        }
      }
    }
  }

  companion object {
    private const val LESSON_ID = "l1_welcome"
  }
}
