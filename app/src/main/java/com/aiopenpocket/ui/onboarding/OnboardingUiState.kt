package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

/**
 * Шаги мастера первичной настройки — ровно три: выбрать язык, объяснить, кто я такой, попросить
 * рассказать о себе.
 *
 * Чего здесь СОЗНАТЕЛЬНО нет и почему:
 * - **Персона.** На первом запуске человеку не с чем сравнивать — он не видел ни одного ответа.
 *   Остаётся дефолтной ([OnboardingUiState.selectedPersonaId]) и меняется в Настройках.
 * - **Итоговый экран про промпт/препромпт.** Это термины, а первый экран должен быть без них;
 *   промпт и препромпт разбирает урок «Как ИИ читает твой запрос», где под них подведена почва.
 * - **Квиз.** Проверять на первом запуске нечего: человек ещё ничего не изучал.
 *
 * Композеблы `PersonaSelectionStep` и `WrapupStep` намеренно оставлены в дереве (и покрыты
 * скриншот-тестами напрямую) — к мастеру они больше не подключены, но готовы вернуться.
 *
 * Индикатор прогресса считается от `entries`, поэтому точки пересчитываются сами.
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
  /** Мастер её не спрашивает — уходит в настройки как есть (дефолт либо ранее сохранённая). */
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
    OnboardingStep.LANGUAGE, OnboardingStep.INTRO -> "Далее"
    // PROFILE теперь последний шаг: эта кнопка и завершает настройку.
    OnboardingStep.PROFILE -> "Готово"
  }
}
