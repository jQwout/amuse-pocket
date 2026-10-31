package com.aiopenpocket.ui.profile

// @trace profile-screen-7354a7

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aiopenpocket.agent.core.memory.model.Fact
import com.aiopenpocket.agent.core.memory.model.UserProfile
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.settings.UsageSummary
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The baseline of the Profile screen. It renders the real [ProfileContent] on fake state — [ProfileScreen] gets
 * its ViewModel through metroViewModel(), which has no DI graph in Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ProfileScreenshotTest {
  @get:Rule val composeTestRule = createComposeRule()

  @After fun restoreLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun fact(id: String, key: String, value: String, category: String) =
    Fact(id, key, value, category, 1L, 1L)

  private val filled = ProfileUiState(
    loaded = true,
    draft = UserProfile(
      name = "Анна",
      preferredAddress = "Аня",
      age = "31",
      gender = "женский",
      extraInfo = "Люблю горы, учу сербский, веду бюджет.",
    ),
    persisted = UserProfile(
      name = "Анна",
      preferredAddress = "Аня",
      age = "31",
      gender = "женский",
      extraInfo = "Люблю горы, учу сербский, веду бюджет.",
    ),
    factSections = listOf(
      FactSection(FactGroup.ABOUT_YOU, listOf(fact("1", "user.city", "Белград", "profile"))),
      FactSection(FactGroup.PREFERENCES, listOf(fact("2", "diet.style", "вегетарианец", "preference"))),
      FactSection(FactGroup.OTHER, listOf(fact("3", "family.sister", "Аня", "general"))),
    ),
    usage = UsageSummary(
      calls = 42,
      promptTokens = 120_000,
      completionTokens = 18_000,
      costUsd = null,
      estimatedCalls = 3,
      backgroundCalls = 5,
      backgroundTokens = 9_000,
      byModel = listOf(UsageSummary.ModelUsage("meta/muse-spark-1.3", 42, 138_000, null)),
    ),
  )

  private fun capture(state: ProfileUiState, path: String) {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        ProfileContent(
          state = state,
          onNavigateBack = {},
          onOpenSettings = {},
          onOpenMemoryGraph = {},
          onFieldChange = { _, _ -> },
          onSave = {},
          onRequestDelete = {},
          onConfirmDelete = {},
          onCancelDelete = {},
        )
      }
    }
    composeTestRule.onRoot().captureRoboImage(filePath = path)
  }

  @Test
  fun profile_filled_screenshot() = capture(filled, "src/test/screenshots/profile_filled.png")

  @Test
  fun profile_empty_english_screenshot() {
    AppLanguage.code = AppLanguage.EN
    capture(ProfileUiState(loaded = true), "src/test/screenshots/profile_empty_en.png")
  }
}
