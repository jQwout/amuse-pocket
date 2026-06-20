package com.aiopenpocket.ui.onboarding

enum class OnboardingStep {
  LANGUAGE,
  INTRO,
  PERSONA,
  PROFILE,
  WRAPUP,
  QUIZ,
}

data class OnboardingUiState(
  val currentStep: OnboardingStep = OnboardingStep.LANGUAGE,
  val selectedLanguage: String = "ru",
  val introStreamingText: String = "",
  val isIntroStreaming: Boolean = false,
  val isIntroComplete: Boolean = false,
  val introAlreadyPlayed: Boolean = false,
  val selectedPersonaId: String = "sharp_partner",
  val name: String = "",
  val age: String = "",
  val gender: String = "",
  val religion: String = "",
  val preferredAddress: String = "",
  val extraInfo: String = "",
  val isSaving: Boolean = false,
  val isCompleted: Boolean = false,
  val error: String? = null,
) {
  val canProceed: Boolean = when (currentStep) {
    OnboardingStep.LANGUAGE -> selectedLanguage.isNotBlank()
    OnboardingStep.INTRO -> isIntroComplete
    OnboardingStep.PERSONA -> selectedPersonaId.isNotBlank()
    OnboardingStep.PROFILE -> name.isNotBlank() && preferredAddress.isNotBlank() && !isSaving
    OnboardingStep.WRAPUP -> !isSaving
    OnboardingStep.QUIZ -> !isSaving
  }

  val primaryButtonLabel: String = when (currentStep) {
    OnboardingStep.LANGUAGE, OnboardingStep.INTRO, OnboardingStep.PERSONA -> "Далее"
    OnboardingStep.PROFILE, OnboardingStep.WRAPUP -> "Далее"
    OnboardingStep.QUIZ -> "Завершить"
  }
}
