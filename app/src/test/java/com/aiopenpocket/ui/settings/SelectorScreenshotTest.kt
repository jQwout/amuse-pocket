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
 * Эталоны селекторов настроек. До этого вёрстка выбора не была прикрыта ничем, а выглядела она
 * разнородно: «Модель»/«Тема» открывали стоковый `DropdownMenu` (плоская непрозрачная панель поверх
 * авроры), «Персона» — стеклянный лист. Теперь у всех трёх одна строка и один лист, и эти два
 * эталона это фиксируют.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SelectorScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /** Все три строки рядом — доказательство, что они одной формы, а не «примерно похожи». */
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
   * Содержимое листа выбора. Снимаем [SelectorSheetContent], а не [SelectorSheet]: сам
   * `ModalBottomSheet` — popup-окно, и `onRoot()` его не захватывает.
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
   * Дубли в списке не должны ронять лист. Список моделей приходит из каталога стороннего сервиса,
   * а `LazyColumn` с `key = { it }` на двух одинаковых строках бросает IllegalArgumentException —
   * то есть настройки переставали бы открываться из-за чужих данных.
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
