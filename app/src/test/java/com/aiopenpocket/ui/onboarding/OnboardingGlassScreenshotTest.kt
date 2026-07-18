package com.aiopenpocket.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.onboarding.components.AboutMeStep
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.PersonaCard
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
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
 * Скриншот-эталоны glass-рестайла онбординга (Task 5, Plan B): аврора-фон, [GlassCard] на строках
 * языка и карточках персоны, стеклянный ввод ([AboutMeStep]), стеклянная обвязка топ-бара/
 * «Назад-Далее»-панели ([OnboardingContent]) и собственный топбар+бар-[Scaffold] шага выбора
 * персоны ([PersonaSelectionStep]). Ни один из этих композеблов не тянет ViewModel напрямую (state —
 * обычный параметр), поэтому рендерим их напрямую без *PreviewScaffold-обёрток — в отличие от
 * Home/Chat/Agents, которым такая обёртка требовалась из-за metroViewModel() внутри экрана.
 * IntroStreamingStep/WrapupStep/StreamingBubble Task 5 не трогает (см. бриф) и здесь не покрываются.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class OnboardingGlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** Строки языка (RU выбрана+включена, EN/DE выключены) — [GlassCard] тонировка/бордер/lock-иконка. */
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

  /** Карточки персоны — выбранная (accent-бордер+бейдж) и невыбранная, обе на [GlassCard]. */
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

  /** «Расскажи о себе» — стеклянное поле ввода (прозрачные containerColor/borderColor поверх Box-стекла). */
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

  /** Шаг выбора персоны целиком — свой Scaffold: GlassSurface на топбаре и на Back/Apply-панели. */
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

  /** Обвязка шага LANGUAGE целиком — аврора + прозрачный топбар + GlassSurface «Назад-Далее». */
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

  /** Та же обвязка на шаге PROFILE — стеклянный [AboutMeStep] под прокручиваемым glassSource. */
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
