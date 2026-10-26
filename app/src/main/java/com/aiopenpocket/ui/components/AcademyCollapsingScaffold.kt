package com.aiopenpocket.ui.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource

/**
 * A screen scaffold with a collapsing toolbar: a large serif title on top that smoothly collapses into a
 * compact pinned strip as the list scrolls (a classic collapsing toolbar). The content must be scrollable
 * (LazyColumn / verticalScroll) so that nested scrolling drives the collapse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademyCollapsingScaffold(
  title: String,
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.background,
  glassState: GlassState? = null,
  navigationIcon: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  // When [glassState] is passed, a separate matchParentSize aurora source layer goes under the scaffold
  // (as on the "Chat" tab/HomeScreen): the scaffold and its bars are transparent, the floating navbar blurs the aurora.
  val effectiveContainer = if (glassState != null) Color.Transparent else containerColor
  Box(Modifier.fillMaxSize()) {
    if (glassState != null) {
      Box(
        Modifier
          .matchParentSize()
          .auroraBackground()
          .glassSource(glassState),
      )
    }
    Scaffold(
      modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      containerColor = effectiveContainer,
      topBar = {
        val bar: @Composable () -> Unit = {
          LargeTopAppBar(
            title = {
              Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = effectiveContainer,
              // The same color in the collapsed state — without the elevation tint (or transparent over the aurora).
              scrolledContainerColor = effectiveContainer,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
              navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
              actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            scrollBehavior = scrollBehavior,
            // In glass mode the status bar inset is moved onto the pill itself (below), so the bar itself does NOT
            // add its inset — otherwise it would double and the pill would slide under the clock/cutout.
            // The same trick as the chat's floating header.
            windowInsets = if (glassState != null) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
          )
        }
        if (glassState != null) {
          // A floating glass island — but one that SHOWS UP as the bar collapses.
          //
          // At the top of the list (the bar expanded) no content has slid under it yet: no backing is
          // needed, and a large title in a filled pill reads as a heavy dark slab and fights the idea of
          // "content takes everything". So the large title lies right on the canvas. As soon as the list
          // moves under the bar, the glass gains density and protects readability. That is exactly how the
          // system iOS bar behaves too.
          //
          // collapsedFraction is read INSIDE graphicsLayer (the draw phase), not in composition: otherwise
          // every scroll frame would recompose the whole top bar. The same trick as the chat feed.
          // The pill's margins live on THIS Box, NOT on the glass inside. If they were hung on GlassSurface
          // after matchParentSize, the padding would shrink the glass itself: it would become 16dp shorter
          // than the bar, whose content is laid out at full height — the title almost touches the edge and
          // the pill looks small (especially where there is neither a back button nor actions, and the title
          // is alone in a wide capsule). Here the margins shift BOTH, and the glass matches the bar's area
          // exactly.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(horizontal = 12.dp)
              .padding(top = 8.dp, bottom = 8.dp),
          ) {
            GlassSurface(
              state = glassState,
              shape = TOP_ISLAND_SHAPE,
              modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                .shadow(16.dp, TOP_ISLAND_SHAPE, clip = false),
            ) {}
            bar()
          }
        } else {
          bar()
        }
      },
      bottomBar = bottomBar,
      floatingActionButton = floatingActionButton,
      content = { padding ->
        if (glassState != null) {
          // The content is ALSO a source for the glass: then the top island and the bottom navbar blur the real
          // list, not just the static aurora (otherwise the glass reads as a flat slab — see
          // ChatScreen.kt:396-400). The Scaffold's content slot contains no bars, so there is no loop.
          Box(
            Modifier
              .fillMaxSize()
              .glassSource(glassState),
          ) {
            content(padding)
          }
        } else {
          content(padding)
        }
      },
    )
  }
}

/** The shape of the top floating island — the same "pill" as the chat bars and the bottom navigation. */
private val TOP_ISLAND_SHAPE = RoundedCornerShape(24.dp)
