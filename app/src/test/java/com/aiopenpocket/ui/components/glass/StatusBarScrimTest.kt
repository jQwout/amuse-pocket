package com.aiopenpocket.ui.components.glass

// @trace glass-design-system-21a3fe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.InkBg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the device, chat text and lesson cards were readable THROUGH the clock and the battery: the band under
 * the status bar started at 70% density and faded to nothing by the BOTTOM edge of the status bar — exactly
 * where the first line of content lies. These tests state the shape the fix has to keep: dense all the way
 * down the status bar, and dissolving only below it.
 */
class StatusBarScrimTest {

  private val inset = 48.dp

  @Test
  fun the_band_stays_dense_for_the_whole_height_of_the_status_bar() {
    val stops = statusBarScrimStops(InkBg, inset, tail = 16.dp)
    val holdTo = stops[1].first
    // 48 / (48 + 16): the dense part must reach the bottom edge of the status bar, not stop short of it.
    assertEquals("the dense part must end exactly at the status bar's bottom edge", 0.75f, holdTo, 0.001f)
    assertEquals("and be just as dense there as at the top", stops[0].second, stops[1].second)
  }

  /**
   * The band is a floating plate like the bars, so it must be as dense as they are — the density that
   * [GlassPlateOpacityTest] holds to the owner's "text under it must not be readable", not a second number
   * drifting on its own. It used to be exactly that: its own 0.94 next to the bars' own 0.94.
   */
  @Test
  fun the_band_is_as_dense_as_the_floating_bars() {
    val stops = statusBarScrimStops(InkBg, inset, tail = 16.dp)
    // The delta is 0.002, not something tighter: Color keeps each channel as a float16, so an alpha that
    // went through Color.copy comes back a ten-thousandth off and a stricter check fails on arithmetic
    // rather than on a real drift (it did, the first time this ran).
    assertEquals(GlassTokens.PlateAlpha, stops[0].second.alpha, 0.002f)
  }

  @Test
  fun the_band_dissolves_below_the_status_bar() {
    val stops = statusBarScrimStops(InkBg, inset, tail = 16.dp)
    assertEquals("the band must end transparent, not as a visible strip", Color.Transparent, stops.last().second)
    assertEquals(1f, stops.last().first, 0.001f)
  }

  /** With no status bar there is nothing to protect, and a stripe of background would be a defect of its own. */
  @Test
  fun without_a_status_bar_there_is_no_band() {
    assertTrue(statusBarScrimStops(InkBg, 0.dp).isEmpty())
    assertTrue(statusBarScrimStops(InkBg, (-4).dp).isEmpty())
  }
}
