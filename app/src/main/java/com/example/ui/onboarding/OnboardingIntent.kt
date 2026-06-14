package com.aiopenpocket.ui.onboarding

sealed class OnboardingIntent {
  data object StartIntroStreaming : OnboardingIntent()
  data object NextStep : OnboardingIntent()
  data object PreviousStep : OnboardingIntent()
  data class SelectPersona(val personaId: String) : OnboardingIntent()
  data class UpdateName(val value: String) : OnboardingIntent()
  data class UpdateAge(val value: String) : OnboardingIntent()
  data class UpdateGender(val value: String) : OnboardingIntent()
  data class UpdatePreferredAddress(val value: String) : OnboardingIntent()
  data class UpdateExtraInfo(val value: String) : OnboardingIntent()
  data object Complete : OnboardingIntent()
}
