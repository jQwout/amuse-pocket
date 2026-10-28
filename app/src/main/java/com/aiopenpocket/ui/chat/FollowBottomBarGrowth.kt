package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Moves the chat feed on when the BOTTOM BAR GROWS while no new message arrives with it.
 *
 * In [ChatScreen] the bottom overlay (the composer plus every bar above it) measures itself and its
 * height becomes the feed's `contentPadding.bottom`, so messages scroll under the glass instead of
 * hiding beneath it. Padding alone, though, does not move the feed: a `LazyColumn` anchors its scroll
 * at the TOP (`firstVisibleItem`), so when the overlay grows the content stays exactly where it was and
 * the last card ends up UNDER the now-taller overlay — the two reading through each other. Seen in the
 * lesson «Как модель видит»: the step's red problem banner drawn over the card's "Show an example"
 * button, the composer's placeholder over "Take my own". Scrolling down by hand fixed it, which is
 * precisely the tell that only the scroll position was wrong.
 *
 * The feed's own autoscroll cannot cover this: it is keyed on the id of the last message, and none of
 * the things that grow the bar — a step's problem, the turn's error with "Retry", the tool-trace panel,
 * the tool-approval bar, the plan card, the keyboard, a composer that outgrew one line — is a message.
 *
 * So the feed is moved on by exactly the growth, and only while [followEnabled] — a reader who went up
 * the history on purpose must not have the feed yanked out from under them; that would be a new defect
 * in place of the old one. A programmatic scroll emits no `DragInteraction`, so this never unsticks the
 * caller's own "pinned to the bottom" flag. Shrinking is not compensated: it only frees space below the
 * last card and hides nothing.
 *
 * @param bottomBarHeightPx the overlay's measured height, the same value that feeds `contentPadding`.
 * @param followEnabled the feed is stuck to the bottom right now and may be moved (see [ChatScreen]).
 * @param resetKey identity of the feed's content (the conversation id): a different one starts the
 *   baseline over, so switching chats is not read as growth.
 */
@Composable
internal fun FollowBottomBarGrowth(
    listState: LazyListState,
    bottomBarHeightPx: Int,
    followEnabled: Boolean,
    resetKey: Any?,
) {
    // The bar height the feed has already been moved for. It starts at whatever the bar measures right now,
    // so a change of [resetKey] is not read as growth: on a conversation switch the bar is already as tall
    // as it is, and nothing is hiding behind it yet.
    var compensatedPx by remember(resetKey) { mutableStateOf(bottomBarHeightPx) }
    LaunchedEffect(bottomBarHeightPx) {
        val grewBy = bottomBarHeightPx - compensatedPx
        // Shrinking hides nothing — it only frees space under the last card: just move the baseline, so that
        // a bar which shrinks and grows back (a banner that leaves and returns) is followed the second time.
        if (grewBy <= 0) {
            compensatedPx = bottomBarHeightPx
            return@LaunchedEffect
        }
        if (!followEnabled) return@LaunchedEffect
        // One frame of wait: the growth arrives through the overlay's onSizeChanged, which fires in a
        // layout pass where the feed has ALREADY been measured with the OLD padding. A scroll now would be
        // clamped by the old range ("we are at the very bottom") and the delta would be lost; after the
        // frame the feed is remeasured with the new contentPadding and the room to scroll into exists.
        withFrameNanos { }
        listState.animateScrollBy(grewBy.toFloat())
        // The baseline moves only once the feed has really caught up: an animation cut short (the reader
        // grabbed the feed, the bar grew again) leaves its uncompensated tail in the NEXT delta instead of
        // losing it. Overshooting is harmless — the feed clamps at its own bottom.
        compensatedPx = bottomBarHeightPx
    }
}
