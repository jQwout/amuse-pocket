package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Two things the first screens of the English demo got wrong on a device (orchestrator/evidence/M1, F3 and F4). */
class OnboardingTextTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  /**
   * F3: choose English on the language step and the heading switched while the button stayed "Далее". The
   * wizard rebuilds the state with copy() the moment the choice is made, and AppLanguage.code changes
   * AFTER that (the choice is saved asynchronously). A label computed in the constructor kept the old
   * language for good; a label computed when it is read follows the language.
   */
  @Test
  fun `the primary button label follows a language chosen after the state was built`() {
    AppLanguage.code = AppLanguage.RU
    val before = OnboardingUiState(currentStep = OnboardingStep.LANGUAGE)
    assertEquals("Далее", before.primaryButtonLabel)

    // The order of OnboardingViewModel.SelectLanguage: the state is copied first, the language changes later.
    val picked = before.copy(selectedLanguage = AppLanguage.EN)
    AppLanguage.code = AppLanguage.EN

    assertEquals("Next", picked.primaryButtonLabel)
    // The same holds for a state nobody copied: the label is read, not remembered.
    assertEquals("Next", before.primaryButtonLabel)
  }

  @Test
  fun `the last step says Done in the language in force`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals("Done", OnboardingUiState(currentStep = OnboardingStep.PROFILE).primaryButtonLabel)
    AppLanguage.code = AppLanguage.RU
    assertEquals("Готово", OnboardingUiState(currentStep = OnboardingStep.PROFILE).primaryButtonLabel)
  }

  /**
   * F4: "This is **AI Open Pocket** — ..." showed its asterisks. The intro streams into StreamingBubble, which
   * draws plain text, so a markdown marker in the text is shown as it is.
   */
  @Test
  fun `the intro text has no markdown markers, in either language`() {
    for (language in listOf(AppLanguage.EN, AppLanguage.RU)) {
      AppLanguage.code = language
      val intro = OnboardingContent.INTRO_TEXT
      assertFalse("asterisks in the $language intro", intro.contains("*"))
      assertFalse("underscore emphasis in the $language intro", Regex("(^|\\s)_\\w").containsMatchIn(intro))
    }
  }
}
