package com.aiopenpocket.ui.chat.components

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import com.aiopenpocket.agent.core.llm.SegmentBox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the frames land on the photo. Wrong numbers here do not crash anything: the frames just sit next to the
 * object, and the whole point of the step is that they sit on it.
 */
class SegmentBoxLayoutTest {

  /** x2/y2 are inclusive: `x1=0;x2=4` covers five pixels, so on a 20-wide picture shown 200 wide it is 50 wide, not 40. */
  @Test
  fun `the far corner is inclusive`() {
    val frame = scaleBox(SegmentBox(x1 = 0, y1 = 0, x2 = 4, y2 = 4, width = 20, height = 10), viewWidth = 200f, viewHeight = 100f)

    assertEquals(0f, frame.left, 1e-4f)
    assertEquals(0f, frame.top, 1e-4f)
    assertEquals(50f, frame.width, 1e-4f)
    assertEquals(50f, frame.height, 1e-4f)
  }

  /** The scale is view size over the w/h in the answer — the picture on screen is rarely the size that was sent. */
  @Test
  fun `scales by the size the model reported`() {
    // The brief's example: a 1516 x 1600 picture, a box from (181,365) to (556,486) inclusive.
    val box = SegmentBox(x1 = 181, y1 = 365, x2 = 556, y2 = 486, width = 1516, height = 1600)

    val frame = scaleBox(box, viewWidth = 379f, viewHeight = 400f) // a quarter of the picture

    assertEquals(181f / 4, frame.left, 1e-3f)
    assertEquals(365f / 4, frame.top, 1e-3f)
    assertEquals(376f / 4, frame.width, 1e-3f)
    assertEquals(122f / 4, frame.height, 1e-3f)
  }

  @Test
  fun `a box offset from the origin keeps its position`() {
    val frame = scaleBox(SegmentBox(x1 = 10, y1 = 20, x2 = 19, y2 = 39, width = 100, height = 80), viewWidth = 100f, viewHeight = 80f)

    assertEquals(10f, frame.left, 1e-4f)
    assertEquals(20f, frame.top, 1e-4f)
    assertEquals(10f, frame.width, 1e-4f)
    assertEquals(20f, frame.height, 1e-4f)
  }

  /** A box that fills the whole picture fills the whole view, down to the last pixel. */
  @Test
  fun `the whole picture maps to the whole view`() {
    val frame = scaleBox(SegmentBox(x1 = 0, y1 = 0, x2 = 639, y2 = 426, width = 640, height = 427), viewWidth = 320f, viewHeight = 213.5f)

    assertEquals(320f, frame.width, 1e-3f)
    assertEquals(213.5f, frame.height, 1e-3f)
  }

  @Test
  fun `the label is the request and the confidence in whole percent`() {
    assertEquals("cup 96%", segmentLabel("cup", 0.96))
    assertEquals("cup 5%", segmentLabel("cup", 0.05))
    assertEquals("cup 100%", segmentLabel("cup", 1.0))
    // A missing confidence is left out, not shown as 0%.
    assertEquals("cup", segmentLabel("cup", null))
    assertEquals("cup 0%", segmentLabel("cup", 0.0))
  }
}
