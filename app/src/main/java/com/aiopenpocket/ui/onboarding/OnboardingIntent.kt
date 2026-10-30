package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

sealed class OnboardingIntent {
  data object StartIntroStreaming : OnboardingIntent()
  data object NextStep : OnboardingIntent()
  data object PreviousStep : OnboardingIntent()
  data class SelectLanguage(val code: String) : OnboardingIntent()
  data class SelectPersona(val personaId: String) : OnboardingIntent()
  data class UpdateAboutMe(val value: String) : OnboardingIntent()
  data class UpdateMetaKey(val value: String) : OnboardingIntent()
  /** "Skip" on the key step: go on without saving anything; the key can be added in Settings later. */
  data object SkipMetaKey : OnboardingIntent()
  data object Complete : OnboardingIntent()
}
