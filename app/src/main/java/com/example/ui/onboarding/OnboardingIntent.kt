package com.aiopenpocket.ui.onboarding

sealed class OnboardingIntent {
  data object NextStep : OnboardingIntent()
  data object PreviousStep : OnboardingIntent()
  /** Skip the current text step (same as Next for text-only steps). */
  data object SkipStep : OnboardingIntent()

  /** Open the "Спросить" bottom sheet for the current text step. */
  data object OpenAsk : OnboardingIntent()
  data object CloseAsk : OnboardingIntent()
  /** Send a free-form question about the current step to the LLM. */
  data class SendAsk(val text: String) : OnboardingIntent()
  data object CancelAsk : OnboardingIntent()

  data class SelectLanguage(val code: String) : OnboardingIntent()
  data class SelectPersona(val personaId: String) : OnboardingIntent()
  data class UpdateName(val value: String) : OnboardingIntent()
  data class UpdateAge(val value: String) : OnboardingIntent()
  data class UpdateGender(val value: String) : OnboardingIntent()
  data class UpdateReligion(val value: String) : OnboardingIntent()
  data class UpdatePreferredAddress(val value: String) : OnboardingIntent()
  data class UpdateExtraInfo(val value: String) : OnboardingIntent()
}
