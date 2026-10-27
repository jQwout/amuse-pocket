package com.aiopenpocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * Baselines of the settings selectors. Before this the choice layout was not covered by anything, and it
 * looked inconsistent: "Model"/"Theme" opened a stock `DropdownMenu` (a flat opaque panel over the
 * aurora), "Persona" a glass sheet. Now all three have one row and one sheet, and these two baselines pin
 * that down.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SelectorScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** All three rows side by side — proof that they have one shape, not "roughly similar" ones. */
  @Test
  fun settings_selector_rows_dark() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          SelectorCard(
            label = "ТЕКУЩАЯ МОДЕЛЬ",
            selected = "deepseek-v4-flash",
            icon = Icons.Default.Memory,
            onClick = {},
            modifier = Modifier.padding(horizontal = 20.dp),
            testTag = "settings_model_selector",
          )
          SelectorCard(
            label = "ОФОРМЛЕНИЕ",
            selected = "Тёмная",
            icon = Icons.Default.Palette,
            onClick = {},
            modifier = Modifier.padding(horizontal = 20.dp),
            testTag = "settings_theme_selector",
          )
          PersonaSelectorCard(
            personaId = "sharp_partner",
            displayName = "Резкий напарник",
            onClick = {},
            modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
      }
    }

    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/settings_selector_rows.png")
  }

  /**
   * The content of the choice sheet. We capture [SelectorSheetContent], not [SelectorSheet]: the
   * `ModalBottomSheet` itself is a popup window, and `onRoot()` does not capture it.
   */
  @Test
  fun settings_selector_sheet_dark() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
        ) {
          SelectorSheetContent(
            title = "Выбор модели",
            subtitle = "На чём агент будет думать. Быстрее — не всегда умнее.",
            options = listOf("deepseek-v4-flash", "minimax-m3", "qwen3-next-80b"),
            selected = "deepseek-v4-flash",
            onSelect = {},
          )
        }
      }
    }

    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/settings_selector_sheet.png")
  }

  /**
   * Duplicates in the list must not crash the sheet. The model list comes from a third-party service's
   * catalog, and a `LazyColumn` with `key = { it }` throws IllegalArgumentException on two equal rows —
   * that is, the settings would stop opening because of someone else's data.
   */
  @Test
  fun duplicate_options_do_not_crash_the_sheet() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        SelectorSheetContent(
          title = "Выбор модели",
          subtitle = null,
          options = listOf("AUTO", "vendor/model", "vendor/model"),
          selected = "AUTO",
          onSelect = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("settings_selector_sheet").assertExists()
  }
}
