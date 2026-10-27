package com.aiopenpocket.ui.chat.components

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import com.aiopenpocket.agent.core.llm.SegmentBox

/** A box in the coordinates of the view it is drawn on. Pure numbers, no Compose types, so plain JUnit covers it. */
internal data class ScaledBox(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Maps SAM's box onto a view that shows the whole picture stretched to [viewWidth] x [viewHeight].
 *
 * The scale is the view's size over the `w`/`h` the MODEL reported for the picture (they come with every
 * record), not over what the app believes it sent: the box is in the model's grid, and that grid is the only
 * one the numbers are true in. The far corner is inclusive on the wire (`x2=4` is the fifth pixel), so the
 * drawn width is `x2 + 1 - x1` — dropping that `+ 1` shrinks every frame by a pixel on a 1280-wide picture
 * and by a visible sliver on a small one.
 */
internal fun scaleBox(box: SegmentBox, viewWidth: Float, viewHeight: Float): ScaledBox {
  val scaleX = viewWidth / box.width
  val scaleY = viewHeight / box.height
  return ScaledBox(
    left = box.left * scaleX,
    top = box.top * scaleY,
    width = (box.right - box.left) * scaleX,
    height = (box.bottom - box.top) * scaleY,
  )
}

/**
 * The frame's caption. SAM's answer carries no class label, so the label is the learner's own request —
 * the model found "cup" because it was asked for "cup". The confidence follows when the answer had one; a
 * missing confidence is left out rather than shown as 0%.
 */
internal fun segmentLabel(concept: String, confidence: Double?): String =
  if (confidence == null) concept else "$concept ${Math.round(confidence * 100)}%"
