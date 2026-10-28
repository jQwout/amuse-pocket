package com.aiopenpocket.ui.components

// @trace glass-design-system-21a3fe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bottom navigation floats OVER the list, so the only thing keeping the last card reachable is the
 * content padding the screen reserves for it. On the device the last card on "Путь" could not be tapped —
 * the tap went into the navbar — so this pins the contract the Path/Tools/Chat tabs all rely on: a list that
 * routes `padding.calculateBottomPadding()` into its `contentPadding` can be scrolled until its last item is
 * clear of the pill, and a tap there reaches the item, not a tab.
 *
 * The assertions are deliberately on the real geometry (bounds) and on a real injected touch (which goes
 * through hit testing, so an overlapping pill would swallow it), not on state the test sets itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BottomNavContentClearanceTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val lastIndex = ITEM_COUNT - 1

  @Test
  fun last_item_clears_the_floating_pill_and_keeps_its_tap() {
    var itemTaps = 0
    var tabTaps = 0
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        // The glass variant, as on the "Путь" tab.
        val glassState = rememberGlassState()
        AcademyCollapsingScaffold(
          title = "Path",
          glassState = glassState,
          bottomBar = {
            AcademyBottomNav(
              selectedTab = AcademyTab.Path,
              onTabSelected = { tabTaps++ },
              glassState = glassState,
            )
          },
        ) { padding ->
          LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
            contentPadding = PaddingValues(
              top = padding.calculateTopPadding(),
              bottom = padding.calculateBottomPadding(),
            ),
          ) {
            items((0 until ITEM_COUNT).toList()) { index ->
              Text(
                text = "Level $index",
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag(itemTag(index))
                  .clickable { itemTaps++ }
                  .padding(24.dp),
              )
            }
          }
        }
      }
    }

    composeTestRule.onNodeWithTag(LIST_TAG).performScrollToIndex(lastIndex)
    composeTestRule.waitForIdle()

    val lastItem = composeTestRule.onNodeWithTag(itemTag(lastIndex)).getUnclippedBoundsInRoot()
    val pill = composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).getUnclippedBoundsInRoot()
    assertTrue(
      "the pill must float over the list, not be stacked under it: pill.top=${pill.top}, pill.bottom=${pill.bottom}",
      pill.bottom > pill.top,
    )
    assertTrue(
      "the last item must end above the navigation pill, otherwise it cannot be tapped: " +
        "item.bottom=${lastItem.bottom}, pill.top=${pill.top}",
      lastItem.bottom <= pill.top,
    )

    composeTestRule.onNodeWithTag(itemTag(lastIndex)).performTouchInput { click() }
    composeTestRule.waitForIdle()
    assertEquals("the tap must reach the last item", 1, itemTaps)
    assertEquals("the tap must not reach a navigation tab", 0, tabTaps)
  }
}

private const val ITEM_COUNT = 30
private const val LIST_TAG = "clearance_list"

private fun itemTag(index: Int) = "clearance_item_$index"
