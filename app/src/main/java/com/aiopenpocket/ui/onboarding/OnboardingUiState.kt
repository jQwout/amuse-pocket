package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

import com.aiopenpocket.i18n.tr

/**
 * The steps of the initial setup wizard — exactly three: choose a language, explain who I am, ask the person
 * to tell about themselves.
 *
 * What is DELIBERATELY not here and why:
 * - **Persona.** On the first launch a person has nothing to compare with — they have not seen a single
 *   answer. It stays the default ([OnboardingUiState.selectedPersonaId]) and is changed in Settings.
 * - **A final screen about the prompt/preprompt.** Those are terms, and the first screen must do without them;
 *   the prompt and the preprompt are covered by the "How AI reads your request" lesson, where the ground is
 *   prepared for them.
 * - **A quiz.** There is nothing to check on the first launch: the person has not studied anything yet.
 *
 * The `PersonaSelectionStep` and `WrapupStep` composables are deliberately left in the tree (and covered by
 * screenshot tests directly) — they are no longer wired into the wizard, but are ready to come back.
 *
 * The progress indicator is computed from `entries`, so the dots recount themselves.
 */
enum class OnboardingStep {
  LANGUAGE,
  INTRO,
  PROFILE,
}

data class OnboardingUiState(
  val currentStep: OnboardingStep = OnboardingStep.LANGUAGE,
  val selectedLanguage: String = "ru",
  val introStreamingText: String = "",
  val isIntroStreaming: Boolean = false,
  val isIntroComplete: Boolean = false,
  val introAlreadyPlayed: Boolean = false,
  /** The wizard does not ask for it — it goes to the settings as is (the default or the one saved earlier). */
  val selectedPersonaId: String = "sharp_partner",
  val aboutMeText: String = "",
  val isExtracting: Boolean = false,
  val extractionError: String? = null,
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
    OnboardingStep.PROFILE -> aboutMeText.isNotBlank() && !isExtracting && !isSaving
  }

  val primaryButtonLabel: String = when (currentStep) {
    OnboardingStep.LANGUAGE, OnboardingStep.INTRO -> tr("Далее", "Next")
    // PROFILE is the last step now: this button also finishes the setup.
    OnboardingStep.PROFILE -> tr("Готово", "Done")
  }
}
