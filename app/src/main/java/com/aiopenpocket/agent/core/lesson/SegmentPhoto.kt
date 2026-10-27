package com.aiopenpocket.agent.core.lesson

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * A photo ready for SAM: what is sent ([bytes], JPEG) and the same picture kept on disk ([file]) so the
 * result card can show it under the boxes.
 *
 * [width] x [height] are the pixels of the JPEG, AFTER the EXIF rotation was applied to them. SAM answers in the
 * pixels of the picture it received, so the boxes are drawn on this very picture and in this very grid —
 * which is why the rotation is baked into the pixels here instead of being left to the file's orientation tag.
 */
class PreparedPhoto(
  val file: File,
  val bytes: ByteArray,
  val width: Int,
  val height: Int,
) {
  val mimeType: String get() = "image/jpeg"
}

/** The longest side sent to SAM. More buys nothing for outlining objects and only slows the upload. */
internal const val SEGMENT_MAX_SIDE = 1280
internal const val SEGMENT_JPEG_QUALITY = 85

/**
 * An EXIF orientation as "rotate clockwise by [rotationDegrees], then mirror left-to-right if
 * [flipHorizontal]" — the same table image libraries (Glide, Coil) use. A phone camera stores the sensor's
 * pixels plus this tag, so a portrait photo is a landscape grid with orientation 6; [BitmapFactory] ignores the tag.
 */
internal data class ExifTransform(val rotationDegrees: Int, val flipHorizontal: Boolean) {
  /** A quarter turn exchanges width and height. */
  val swapsAxes: Boolean get() = rotationDegrees % 180 != 0
}

internal fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
  ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
  ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
  ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
  ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
  ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
  ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
  ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
  // NORMAL, UNDEFINED and any value we do not know: leave the pixels alone.
  else -> ExifTransform(0, false)
}

/** The size of a [width] x [height] picture once [transform] is applied. */
internal fun orientedSize(width: Int, height: Int, transform: ExifTransform): Pair<Int, Int> =
  if (transform.swapsAxes) height to width else width to height

/** [width] x [height] scaled down so the longest side is at most [maxSide], keeping the ratio. Never scales up. */
internal fun fitWithin(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
  val longest = maxOf(width, height)
  if (longest <= maxSide) return width to height
  val scale = maxSide.toDouble() / longest
  return Math.round(width * scale).toInt().coerceAtLeast(1) to Math.round(height * scale).toInt().coerceAtLeast(1)
}

/**
 * The largest power-of-two `inSampleSize` that still decodes at least [targetWidth] x [targetHeight]: a 12 MP
 * camera photo is decoded at a quarter of its size straight away instead of in full and then shrunk, which is
 * the difference between a few MB and a hundred.
 */
internal fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
  var sample = 1
  while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) sample *= 2
  return sample
}

/**
 * Turns whatever the user picked into the JPEG that goes to SAM: rotated upright by its EXIF tag, scaled to
 * [SEGMENT_MAX_SIDE], flattened onto white if it has transparency (JPEG has no alpha; black patches would be
 * a different picture from the one the user chose). Written to [outputDir]/[name] for the result card.
 *
 * [open] gives a fresh stream each call: the file is read three times (size, EXIF tag, pixels), and a
 * `content://` stream cannot be rewound. `null` means the source could not be read or is not a picture.
 * Blocking work — call it off the main thread.
 */
internal fun prepareSegmentPhoto(open: () -> InputStream?, outputDir: File, name: String): PreparedPhoto? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  // decodeStream returns null for a bounds-only pass BY DESIGN — the size is in `bounds`, so its result is not
  // checked; an unreadable source and a non-picture both leave the size at 0 or -1.
  (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

  val transform = exifTransform(readOrientation(open))
  val (orientedWidth, orientedHeight) = orientedSize(bounds.outWidth, bounds.outHeight, transform)
  val (targetWidth, targetHeight) = fitWithin(orientedWidth, orientedHeight, SEGMENT_MAX_SIDE)
  // inSampleSize works on the file's own grid, before any rotation.
  val (gridWidth, gridHeight) = if (transform.swapsAxes) targetHeight to targetWidth else targetWidth to targetHeight
  val options = BitmapFactory.Options().apply {
    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, gridWidth, gridHeight)
  }
  val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

  val (decodedOrientedWidth, decodedOrientedHeight) = orientedSize(decoded.width, decoded.height, transform)
  val matrix = Matrix().apply {
    if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
    if (transform.flipHorizontal) postScale(-1f, 1f)
    postScale(targetWidth.toFloat() / decodedOrientedWidth, targetHeight.toFloat() / decodedOrientedHeight)
  }
  var upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
  if (upright !== decoded) decoded.recycle()

  if (upright.hasAlpha()) {
    val flat = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply {
      drawColor(Color.WHITE)
      drawBitmap(upright, 0f, 0f, null)
    }
    upright.recycle()
    upright = flat
  }

  val out = ByteArrayOutputStream()
  val encoded = upright.compress(Bitmap.CompressFormat.JPEG, SEGMENT_JPEG_QUALITY, out)
  val width = upright.width
  val height = upright.height
  upright.recycle()
  if (!encoded) return null

  val bytes = out.toByteArray()
  outputDir.mkdirs()
  val file = File(outputDir, name)
  file.writeBytes(bytes)
  return PreparedPhoto(file, bytes, width, height)
}

/** The EXIF orientation, or "normal" when there is no tag or the source is not a JPEG. */
private fun readOrientation(open: () -> InputStream?): Int =
  try {
    open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
      ?: ExifInterface.ORIENTATION_NORMAL
  } catch (e: Exception) {
    ExifInterface.ORIENTATION_NORMAL
  }
