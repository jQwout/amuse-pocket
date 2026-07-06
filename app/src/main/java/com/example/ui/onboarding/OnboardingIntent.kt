package com.aiopenpocket.ui.onboarding

sealed class OnboardingIntent {
  data object StartIntroStreaming : OnboardingIntent()
  data object NextStep : OnboardingIntent()
  data object PreviousStep : OnboardingIntent()
  data class SelectLanguage(val code: String) : OnboardingIntent()
  data class SelectPersona(val personaId: String) : OnboardingIntent()
  data class UpdateAboutMe(val value: String) : OnboardingIntent()
  data object Complete : OnboardingIntent()
}
