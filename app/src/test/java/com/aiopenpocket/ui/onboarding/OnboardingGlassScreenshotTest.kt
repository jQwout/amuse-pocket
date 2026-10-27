package com.aiopenpocket.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.onboarding.components.AboutMeStep
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.PersonaCard
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
import com.aiopenpocket.ui.onboarding.components.WrapupStep
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baselines of the onboarding glass restyle (Task 5, Plan B): the aurora background,
 * [GlassCard] on the language rows and persona cards, a glass input ([AboutMeStep]), the glass chrome of
 * the top bar / "Back-Next" panel ([OnboardingContent]) and the persona selection step's own top bar +
 * bar [Scaffold] ([PersonaSelectionStep]). None of these composables pulls a ViewModel directly (state is
 * an ordinary parameter), so we render them directly without *PreviewScaffold wrappers — unlike
 * Home/Chat/Agents, which needed such a wrapper because of metroViewModel() inside the screen.
 * IntroStreamingStep/StreamingBubble are not covered here. WrapupStep is covered (dark+light) after the
 * restyle of its cards to the lime accent — it does not use the Task 5 glass chrome, but we check the
 * card colors.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class OnboardingGlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** The language rows (RU selected+enabled, EN/DE disabled) — [GlassCard] tint/border/lock icon. */
  @Test
  fun onboarding_language_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LanguageSelectionStep(selectedLanguage = "ru", onSelectLanguage = {})
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_language_glass.png")
  }

  /** Persona cards — the selected one (accent border + badge) and an unselected one, both on [GlassCard]. */
  @Test
  fun onboarding_persona_card_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          Column(modifier = Modifier.fillMaxWidth()) {
            PersonaCard(persona = PersonaCatalog.all[0], isSelected = true, onSelect = {})
            PersonaCard(persona = PersonaCatalog.all[1], isSelected = false, onSelect = {})
          }
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_persona_glass.png")
  }

  /** "Tell me about yourself" — a glass input field (transparent containerColor/borderColor over the Box glass). */
  @Test
  fun onboarding_about_me_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          AboutMeStep(
            personaId = "sharp_partner",
            text = "Я Саша, 27, разработчик. На «ты».",
            isExtracting = false,
            extractionError = null,
            onTextChange = {},
          )
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_about_me_glass.png")
  }

  /** "How the chat works" (dark) — bubble cards with a lime edge and lime chips on the key words. */
  @Test
  fun onboarding_wrapup_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          WrapupStep(personaId = "sharp_partner", name = "Саша", preferredAddress = "ты")
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_wrapup_glass.png")
  }

  /** The same screen in the light theme — the accent goes olive (primary), a readability check on paper. */
  @Test
  fun onboarding_wrapup_light_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          WrapupStep(personaId = "sharp_partner", name = "Саша", preferredAddress = "ты")
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_wrapup_light.png")
  }

  // The QUIZ step tests were removed together with the step itself: the wizard is now LANGUAGE → INTRO →
  // PROFILE, and the OnboardingStep.QUIZ state no longer exists. The quiz cards as such are covered where
  // the quiz still lives — in the "Agent path" lessons.

  /**
   * The whole persona selection step — its own Scaffold: GlassSurface on the top bar and on the Back/Apply panel.
   *
   * The step was removed from the onboarding wizard (see [OnboardingStep]), but the composable is alive
   * and covered: the persona is changed in Settings, and the step is ready to return to the wizard if we
   * decide to ask for it later.
   */
  @Test
  fun onboarding_persona_step_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        PersonaSelectionStep(
          selectedPersonaId = "sharp_partner",
          isSaving = false,
          error = null,
          onSelectPersona = {},
          onBack = {},
          onApply = {},
        )
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_persona_step_glass.png")
  }

  /** The whole chrome of the LANGUAGE step — aurora + transparent top bar + GlassSurface "Back-Next". */
  @Test
  fun onboarding_language_step_chrome_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        OnboardingContent(
          state = OnboardingUiState(currentStep = OnboardingStep.LANGUAGE, selectedLanguage = "ru"),
          editMode = false,
          onClose = null,
          onIntent = {},
        )
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_language_step_glass.png")
  }

  /** The same chrome on the PROFILE step — the glass [AboutMeStep] under a scrolling glassSource. */
  @Test
  fun onboarding_profile_step_chrome_glass_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        OnboardingContent(
          state = OnboardingUiState(
            currentStep = OnboardingStep.PROFILE,
            selectedPersonaId = "sharp_partner",
            aboutMeText = "Я Саша, 27, разработчик. На «ты».",
          ),
          editMode = false,
          onClose = null,
          onIntent = {},
        )
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/onboarding_profile_step_glass.png")
  }
}
