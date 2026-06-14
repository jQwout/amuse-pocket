package com.aiopenpocket.ui.onboarding

enum class OnboardingStep {
  INTRO,
  PERSONA,
  PROFILE,
}

data class OnboardingUiState(
  val currentStep: OnboardingStep = OnboardingStep.INTRO,
  val introStreamingText: String = "",
  val isIntroStreaming: Boolean = false,
  val isIntroComplete: Boolean = false,
  val introAlreadyPlayed: Boolean = false,
  val selectedPersonaId: String = "sharp_partner",
  val name: String = "",
  val age: String = "",
  val gender: String = "",
  val preferredAddress: String = "",
  val extraInfo: String = "",
  val isSaving: Boolean = false,
  val error: String? = null,
) {
  val canProceed: Boolean = when (currentStep) {
    OnboardingStep.INTRO -> isIntroComplete
    OnboardingStep.PERSONA -> selectedPersonaId.isNotBlank()
    OnboardingStep.PROFILE -> name.isNotBlank() && preferredAddress.isNotBlank() && !isSaving
  }

  val primaryButtonLabel: String = when (currentStep) {
    OnboardingStep.INTRO, OnboardingStep.PERSONA -> "Далее"
    OnboardingStep.PROFILE -> "Начать"
  }
}
