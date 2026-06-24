package com.aiopenpocket.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.StreamEvent
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(OnboardingViewModel::class)
class OnboardingViewModel(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val memoryRepository: MemoryRepository,
  private val llmClient: LlmClient,
) : ViewModel() {

  private val _state = MutableStateFlow(OnboardingUiState())
  val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

  private var introJob: Job? = null
  private var extractionJob: Job? = null

  private val extractionJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  fun handleIntent(intent: OnboardingIntent) {
    when (intent) {
      OnboardingIntent.StartIntroStreaming -> startIntroStreamingIfNeeded()
      OnboardingIntent.NextStep -> goToNextStep()
      OnboardingIntent.PreviousStep -> goToPreviousStep()
      is OnboardingIntent.SelectLanguage -> {
        _state.update { it.copy(selectedLanguage = intent.code) }
      }
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
      OnboardingStep.LANGUAGE -> _state.update { it.copy(currentStep = OnboardingStep.INTRO) }
      OnboardingStep.INTRO -> _state.update { it.copy(currentStep = OnboardingStep.PERSONA) }
      OnboardingStep.PERSONA -> _state.update { it.copy(currentStep = OnboardingStep.PROFILE) }
      OnboardingStep.PROFILE -> extractProfileAndAdvance()
      OnboardingStep.WRAPUP -> _state.update { it.copy(currentStep = OnboardingStep.QUIZ) }
      OnboardingStep.QUIZ -> completeOnboarding()
    }
  }

  private fun goToPreviousStep() {
    when (_state.value.currentStep) {
      OnboardingStep.INTRO -> _state.update { it.copy(currentStep = OnboardingStep.LANGUAGE) }
      OnboardingStep.PERSONA -> _state.update { it.copy(currentStep = OnboardingStep.INTRO) }
      OnboardingStep.PROFILE -> _state.update { it.copy(currentStep = OnboardingStep.PERSONA) }
      OnboardingStep.WRAPUP -> _state.update { it.copy(currentStep = OnboardingStep.PROFILE) }
      OnboardingStep.QUIZ -> _state.update { it.copy(currentStep = OnboardingStep.WRAPUP) }
      OnboardingStep.LANGUAGE -> Unit
    }
  }

  private fun extractProfileAndAdvance() {
    if (extractionJob?.isActive == true) return
    val current = _state.value
    if (current.aboutMeText.isBlank()) return

    extractionJob = viewModelScope.launch {
      _state.update { it.copy(isExtracting = true, extractionError = null) }
      try {
        val raw = collectExtractionText(current.aboutMeText)
        val extracted = parseExtracted(raw)
          ?: throw IllegalStateException("Не удалось разобрать ответ модели")

        memoryRepository.saveUserProfile(
          UserProfile(
            name = extracted.name.trim(),
            preferredAddress = extracted.preferredAddress.trim(),
            age = extracted.age.trim(),
            gender = extracted.gender.trim(),
            religion = extracted.religion.trim(),
            extraInfo = extracted.extraInfo.trim(),
          ),
        )

        _state.update {
          it.copy(
            isExtracting = false,
            name = extracted.name.ifBlank { it.name },
            preferredAddress = extracted.preferredAddress.ifBlank { it.preferredAddress },
            age = extracted.age.ifBlank { it.age },
            gender = extracted.gender.ifBlank { it.gender },
            religion = extracted.religion.ifBlank { it.religion },
            extraInfo = extracted.extraInfo.ifBlank { it.extraInfo },
            currentStep = OnboardingStep.WRAPUP,
          )
        }
      } catch (e: Exception) {
        _state.update {
          it.copy(
            isExtracting = false,
            extractionError = e.message ?: "Не удалось получить ответ модели",
          )
        }
      }
    }
  }

  private suspend fun collectExtractionText(userText: String): String {
    val buffer = StringBuilder()
    var errorMessage: String? = null
    llmClient.streamChat(
      messages = listOf(
        ChatMessage(role = "system", content = EXTRACTION_SYSTEM_PROMPT),
        ChatMessage(role = "user", content = userText),
      ),
      tools = emptyList(),
    ).collect { event ->
      when (event) {
        is StreamEvent.TextDelta -> buffer.append(event.content)
        is StreamEvent.Error -> errorMessage = event.message
        else -> Unit
      }
    }
    if (buffer.isBlank() && errorMessage != null) {
      throw IllegalStateException(errorMessage)
    }
    return buffer.toString()
  }

  private fun parseExtracted(raw: String): ExtractedProfile? {
    val jsonText = stripToJsonObject(raw) ?: return null
    return try {
      extractionJson.decodeFromString(ExtractedProfile.serializer(), jsonText)
    } catch (e: Exception) {
      null
    }
  }

  private fun stripToJsonObject(raw: String): String? {
    val s = raw.trim()
    val fenced = FENCED_JSON.find(s)
    if (fenced != null) return fenced.groupValues[1].trim()
    val start = s.indexOf('{')
    val end = s.lastIndexOf('}')
    if (start >= 0 && end > start) return s.substring(start, end + 1)
    return null
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

  @Serializable
  private data class ExtractedProfile(
    val name: String = "",
    val preferredAddress: String = "",
    val age: String = "",
    val gender: String = "",
    val religion: String = "",
    val extraInfo: String = "",
  )

  companion object {
    private const val STREAM_CHAR_DELAY_MS = 25L

    private val FENCED_JSON = Regex("""```(?:json)?\s*([\s\S]+?)\s*```""")

    private val EXTRACTION_SYSTEM_PROMPT = """
      Ты извлекаешь данные профиля пользователя из его свободного рассказа о себе.
      Верни СТРОГО один JSON-объект на русском, без пояснений и без markdown-обёрток.

      Поля (все строки; пустая строка, если пользователь не упомянул):
      - name: имя
      - preferredAddress: как к нему обращаться (имя, "ты", "вы")
      - age: возраст числом
      - gender: "мужской" | "женский" | ""
      - religion: религиозные предпочтения
      - extraInfo: одна строка с прочим контекстом — занятие, интересы, особенности

      Не выдумывай. Если поле не упомянуто — пустая строка. Ключи именно в camelCase.
    """.trimIndent()
  }
}
