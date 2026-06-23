package com.aiopenpocket.ui.onboarding

import com.aiopenpocket.agent.core.lesson.LessonStep

/** One message in the ephemeral "Спросить" thread for the current step. */
data class AskMessage(val role: String, val content: String)

/**
 * Onboarding = the first lesson ("Анатомия чата"), driven by a list of typed [LessonStep]s
 * loaded from `assets/lessons/l1_welcome.json`. Code advances the steps; setup steps
 * (language/persona/profile) reuse the existing components, text steps teach + offer "Спросить".
 */
data class OnboardingUiState(
  val steps: List<LessonStep> = emptyList(),
  val currentStepIndex: Int = 0,
  val selectedLanguage: String = "ru",
  val selectedPersonaId: String = "sharp_partner",
  val name: String = "",
  val age: String = "",
  val gender: String = "",
  val religion: String = "",
  val preferredAddress: String = "",
  val extraInfo: String = "",
  // "Спросить" bottom sheet — ephemeral, scoped to the current step.
  val askSheetOpen: Boolean = false,
  val askThread: List<AskMessage> = emptyList(),
  val isAsking: Boolean = false,
  val askError: String? = null,
  val isSaving: Boolean = false,
  val isCompleted: Boolean = false,
  val error: String? = null,
) {
  val currentStep: LessonStep? get() = steps.getOrNull(currentStepIndex)

  val isLastStep: Boolean get() = steps.isNotEmpty() && currentStepIndex >= steps.lastIndex

  val canProceed: Boolean
    get() = when (currentStep?.type) {
      "language" -> selectedLanguage.isNotBlank()
      "profile" -> name.isNotBlank() && preferredAddress.isNotBlank() && !isSaving
      "quiz" -> !isSaving
      "text" -> true
      else -> !isSaving
    }

  val primaryButtonLabel: String get() = if (isLastStep) "Завершить" else "Далее"
}
