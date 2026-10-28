package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [FollowBottomBarGrowth] against a real [LazyColumn] whose `contentPadding.bottom` is the bar's height —
 * the very wiring [ChatScreen] has.
 *
 * The defect: when the bar grew (a step's problem banner, the turn's error, the tool trace, the approval
 * bar, the plan card) the padding grew with it and the feed did not move, so the last card stayed under
 * the taller bar. `canScrollForward` is that condition in a single bit — "there is content below the
 * fold". False before the growth and after it means the feed followed; true after it means exactly the
 * grown-by amount of content now sits under the bar, out of reach without a hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FollowBottomBarGrowthTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val barAtRest = 120
  private val barGrown = 320
  private val lastItem = 19

  /** The composed fixture: the feed's state and the handle that resizes the bar over it. */
  private class Fixture(val listState: LazyListState, val setBarHeight: (Int) -> Unit)

  /** A feed taller than the viewport, as a chat with a few cards in it is. */
  private fun fixture(followEnabled: Boolean): Fixture {
    var barHeightPx by mutableStateOf(barAtRest)
    lateinit var listState: LazyListState
    composeTestRule.setContent {
      listState = rememberLazyListState()
      val bottomPad = with(LocalDensity.current) { barHeightPx.toDp() }
      Box(Modifier.size(320.dp)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPad)) {
          items(lastItem + 1) { Box(Modifier.fillMaxWidth().height(60.dp)) }
        }
      }
      FollowBottomBarGrowth(
        listState = listState,
        bottomBarHeightPx = barHeightPx,
        followEnabled = followEnabled,
        resetKey = "conversation",
      )
    }
    composeTestRule.waitForIdle()
    return Fixture(listState) { height ->
      composeTestRule.runOnIdle { barHeightPx = height }
      composeTestRule.waitForIdle()
    }
  }

  private fun Fixture.scrollToTheVeryBottom() {
    composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(lastItem, Int.MAX_VALUE) } }
    composeTestRule.waitForIdle()
    assertFalse("the fixture must start at the very bottom", listState.canScrollForward)
  }

  @Test
  fun `a grown bar takes the feed along, so the last card stays above it`() {
    val fixture = fixture(followEnabled = true)
    fixture.scrollToTheVeryBottom()

    fixture.setBarHeight(barGrown)

    assertFalse(
      "the feed stayed put while the bar grew — the last card is under the bar again",
      fixture.listState.canScrollForward,
    )
  }

  @Test
  fun `a reader who went up the history is not pulled down`() {
    val fixture = fixture(followEnabled = false)
    fixture.scrollToTheVeryBottom()
    val before = fixture.listState.firstVisibleItemIndex to fixture.listState.firstVisibleItemScrollOffset

    fixture.setBarHeight(barGrown)

    // Not a word about the bar: whoever owns the scroll position keeps it, to the pixel.
    assertEquals(
      before,
      fixture.listState.firstVisibleItemIndex to fixture.listState.firstVisibleItemScrollOffset,
    )
    // And this is the proof that the growth really did open room to scroll into — without it the
    // assertion above would hold even if nothing had grown at all.
    assertTrue(fixture.listState.canScrollForward)
  }

  @Test
  fun `a bar that shrinks back and grows again is followed the second time too`() {
    val fixture = fixture(followEnabled = true)
    fixture.scrollToTheVeryBottom()

    // The banner arrives, leaves (the step was answered) and comes back. The baseline has to come down
    // with it, or the second growth would read as "no growth" and nothing would follow it.
    fixture.setBarHeight(barGrown)
    fixture.setBarHeight(barAtRest)
    fixture.setBarHeight(barGrown)

    assertFalse(fixture.listState.canScrollForward)
  }
}
