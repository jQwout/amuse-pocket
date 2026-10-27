package com.aiopenpocket.agent.core.lesson

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The photo that goes to SAM. SAM answers in the pixels of the picture it received, so the boxes are only right if
 * that picture is the one the learner sees — upright. A phone camera saves the sensor's grid plus an EXIF
 * orientation tag, and [BitmapFactory] ignores the tag: without the rotation done here, every frame on a portrait
 * photo would sit on the wrong part of the picture (or outside it).
 *
 * Native graphics: the tests decode and encode real JPEG pixels, so what is checked is where a marker ends up,
 * not what a matrix was asked to do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SegmentPhotoTest {

  @get:Rule
  val tmp = TemporaryFolder()

  // --- the pure parts ---

  @Test
  fun `every EXIF orientation maps to its rotation and mirror`() {
    val expected = mapOf(
      ExifInterface.ORIENTATION_NORMAL to ExifTransform(0, false),
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ExifTransform(0, true),
      ExifInterface.ORIENTATION_ROTATE_180 to ExifTransform(180, false),
      ExifInterface.ORIENTATION_FLIP_VERTICAL to ExifTransform(180, true),
      ExifInterface.ORIENTATION_TRANSPOSE to ExifTransform(90, true),
      ExifInterface.ORIENTATION_ROTATE_90 to ExifTransform(90, false),
      ExifInterface.ORIENTATION_TRANSVERSE to ExifTransform(270, true),
      ExifInterface.ORIENTATION_ROTATE_270 to ExifTransform(270, false),
      ExifInterface.ORIENTATION_UNDEFINED to ExifTransform(0, false),
      99 to ExifTransform(0, false),
    )
    for ((orientation, transform) in expected) assertEquals("orientation $orientation", transform, exifTransform(orientation))
  }

  @Test
  fun `a quarter turn swaps width and height, a half turn does not`() {
    assertEquals(20 to 40, orientedSize(40, 20, exifTransform(ExifInterface.ORIENTATION_ROTATE_90)))
    assertEquals(20 to 40, orientedSize(40, 20, exifTransform(ExifInterface.ORIENTATION_ROTATE_270)))
    assertEquals(20 to 40, orientedSize(40, 20, exifTransform(ExifInterface.ORIENTATION_TRANSPOSE)))
    assertEquals(40 to 20, orientedSize(40, 20, exifTransform(ExifInterface.ORIENTATION_ROTATE_180)))
    assertEquals(40 to 20, orientedSize(40, 20, exifTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)))
  }

  @Test
  fun `fitWithin keeps the ratio and never scales up`() {
    assertEquals(1280 to 720, fitWithin(2560, 1440, 1280))
    assertEquals(720 to 1280, fitWithin(1440, 2560, 1280))
    assertEquals(640 to 427, fitWithin(640, 427, 1280))
    assertEquals(1280 to 1280, fitWithin(1280, 1280, 1280))
    // A sliver stays at least one pixel.
    assertEquals(1280 to 1, fitWithin(100_000, 10, 1280))
  }

  @Test
  fun `sample size is the largest power of two that still covers the target`() {
    assertEquals(1, sampleSizeFor(640, 480, 640, 480))
    assertEquals(1, sampleSizeFor(1000, 800, 800, 640))
    assertEquals(2, sampleSizeFor(2560, 1440, 1280, 720))
    assertEquals(4, sampleSizeFor(4000, 3000, 1000, 750))
    // The decoded picture is never smaller than the target.
    assertEquals(2, sampleSizeFor(4000, 3000, 1280, 960))
  }

  // --- the real pipeline ---

  /** A [w] x [h] picture: blue, a red block in the stored top-left corner, a green one in the stored top-right. */
  private fun markedBitmap(w: Int, h: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLUE)
    canvas.drawRect(0f, 0f, BLOCK.toFloat(), BLOCK.toFloat(), Paint().apply { color = Color.RED })
    canvas.drawRect((w - BLOCK).toFloat(), 0f, w.toFloat(), BLOCK.toFloat(), Paint().apply { color = Color.GREEN })
    return bitmap
  }

  private fun markedJpeg(w: Int, h: Int, orientation: Int?): ByteArray {
    val out = ByteArrayOutputStream()
    check(markedBitmap(w, h).compress(Bitmap.CompressFormat.JPEG, 95, out))
    val jpeg = out.toByteArray()
    return if (orientation == null) jpeg else withExifOrientation(jpeg, orientation)
  }

  private fun markedPng(w: Int, h: Int, orientation: Int): ByteArray {
    val out = ByteArrayOutputStream()
    check(markedBitmap(w, h).compress(Bitmap.CompressFormat.PNG, 100, out))
    return withPngExifOrientation(out.toByteArray(), orientation)
  }

  /** The smallest valid TIFF block with one IFD entry: Orientation (tag 0x0112). */
  private fun exifTiff(orientation: Int): ByteArray = byteArrayOf(
    'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, // big-endian, 42, IFD0 at offset 8
    0, 1, // one entry
    0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, count 1, the value
    0, 0, 0, 0, // no next IFD
  )

  /**
   * Inserts a PNG `eXIf` chunk right after the IHDR chunk. A PNG carries its orientation there, not in a JPEG APP1
   * segment: this is the kind of file a gallery hands over that an EXIF reader limited to JPEG would miss.
   */
  private fun withPngExifOrientation(png: ByteArray, orientation: Int): ByteArray {
    val type = "eXIf".toByteArray(Charsets.US_ASCII)
    val data = exifTiff(orientation)
    val crc = CRC32().apply { update(type); update(data) }.value
    val chunk = ByteBuffer.allocate(4 + type.size + data.size + 4)
      .putInt(data.size).put(type).put(data).putInt(crc.toInt())
      .array()
    // 8-byte signature, then IHDR: 4 length + 4 type + 13 data + 4 CRC.
    val afterHeader = 8 + 25
    return png.copyOfRange(0, afterHeader) + chunk + png.copyOfRange(afterHeader, png.size)
  }

  /** Inserts the smallest valid EXIF block right after the JPEG's SOI marker. */
  private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
    val tiff = exifTiff(orientation)
    val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
    val length = payload.size + 2
    val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
  }

  private fun prepare(bytes: ByteArray): PreparedPhoto? =
    prepareSegmentPhoto({ ByteArrayInputStream(bytes) }, tmp.newFolder(), "photo.jpg")

  private enum class Corner { TL, TR, BL, BR }

  /** The color at the middle of the block that sits in [corner] of [bitmap]. */
  private fun colorAt(bitmap: Bitmap, corner: Corner): Int {
    val half = BLOCK / 2
    val x = if (corner == Corner.TL || corner == Corner.BL) half else bitmap.width - half
    val y = if (corner == Corner.TL || corner == Corner.TR) half else bitmap.height - half
    return bitmap.getPixel(x, y)
  }

  private fun isRed(c: Int) = Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90
  private fun isGreen(c: Int) = Color.green(c) > 150 && Color.red(c) < 100 && Color.blue(c) < 100
  private fun isBlue(c: Int) = Color.blue(c) > 150 && Color.red(c) < 100 && Color.green(c) < 100

  /**
   * Where the stored top-left (red) and top-right (green) corners land when the photo is shown upright. From the
   * EXIF specification, which names for each value where the stored first row and first column lie in the picture
   * (6: row 0 at the right, column 0 at the top, hence the stored top-left lands at the top-right) — written
   * out here from the spec, not derived from the code under test.
   */
  private val landing = mapOf(
    1 to (Corner.TL to Corner.TR),
    2 to (Corner.TR to Corner.TL),
    3 to (Corner.BR to Corner.BL),
    4 to (Corner.BL to Corner.BR),
    5 to (Corner.TL to Corner.BL),
    6 to (Corner.TR to Corner.BR),
    7 to (Corner.BR to Corner.TR),
    8 to (Corner.BL to Corner.TL),
  )

  @Test
  fun `the photo comes out upright for every orientation tag`() = assertUprightForEveryOrientation("JPEG") { markedJpeg(STORED_W, STORED_H, it) }

  /**
   * A PNG (or WebP, HEIC) from the gallery keeps its orientation outside a JPEG APP1 segment. An EXIF reader that only
   * understands JPEG — the platform's, on older Android — leaves such a photo sideways, and the boxes with it.
   */
  @Test
  fun `a PNG with an orientation tag comes out upright too`() = assertUprightForEveryOrientation("PNG") { markedPng(STORED_W, STORED_H, it) }

  private fun assertUprightForEveryOrientation(format: String, make: (orientation: Int) -> ByteArray) {
    for ((orientation, corners) in landing) {
      val photo = prepare(make(orientation))!!
      val bitmap = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)

      val swapped = orientation >= 5
      assertEquals("$format orientation $orientation: width", if (swapped) STORED_H else STORED_W, photo.width)
      assertEquals("$format orientation $orientation: height", if (swapped) STORED_W else STORED_H, photo.height)
      // The recorded size is the size of the pixels that are actually sent — the grid SAM's boxes are in.
      assertEquals("$format orientation $orientation: bitmap width", photo.width, bitmap.width)
      assertEquals("$format orientation $orientation: bitmap height", photo.height, bitmap.height)
      val (red, green) = corners
      assertTrue("$format orientation $orientation: red block should be at $red", isRed(colorAt(bitmap, red)))
      assertTrue("$format orientation $orientation: green block should be at $green", isGreen(colorAt(bitmap, green)))
      // Mirror and rotation bugs show up as the markers swapping places: the other two corners stay blue.
      for (other in Corner.values().filter { it != red && it != green }) {
        assertTrue("$format orientation $orientation: $other should be blue", isBlue(colorAt(bitmap, other)))
      }
    }
  }

  @Test
  fun `a photo with no tag is left as it is`() {
    val photo = prepare(markedJpeg(STORED_W, STORED_H, null))!!

    assertEquals(STORED_W, photo.width)
    assertEquals(STORED_H, photo.height)
    assertEquals("image/jpeg", photo.mimeType)
    // The copy on disk is the very picture that is sent.
    assertTrue(photo.file.isFile)
    assertArrayEquals(photo.bytes, photo.file.readBytes())
  }

  @Test
  fun `a large photo is scaled to the limit after the rotation`() {
    // 3000 x 2000 stored, rotated a quarter turn: 2000 x 3000 upright, so the LONG side is the height and the
    // scale is 1280 / 3000. The decoder can only halve (3000 -> 1500), so the last step has to be an exact scale.
    val photo = prepare(markedJpeg(3000, 2000, ExifInterface.ORIENTATION_ROTATE_90))!!

    assertEquals(853, photo.width)
    assertEquals(1280, photo.height)
    val bitmap = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
    assertEquals(853, bitmap.width)
    assertEquals(1280, bitmap.height)
  }

  @Test
  fun `transparency is flattened onto white, not onto black`() {
    val source = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888) // fully transparent
    val png = ByteArrayOutputStream().also { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()

    val photo = prepare(png)!!

    val pixel = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size).getPixel(24, 16)
    assertTrue("expected white, was ${Integer.toHexString(pixel)}", Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230)
  }

  @Test
  fun `something that is not a picture is no photo`() {
    assertNull(prepare("this is not an image".toByteArray()))
    assertNull(prepareSegmentPhoto({ null }, tmp.newFolder(), "photo.jpg"))
    assertNull(prepare(ByteArray(0)))
  }

  @Test
  fun `the output folder is created and only the photo is written`() {
    val dir = tmp.newFolder().resolve("lesson_segments")
    assertFalse(dir.exists())

    val photo = prepareSegmentPhoto({ ByteArrayInputStream(markedJpeg(STORED_W, STORED_H, null)) }, dir, "segment-1.jpg")

    assertNotNull(photo)
    assertEquals(listOf("segment-1.jpg"), dir.list()!!.toList())
  }

  private companion object {
    const val BLOCK = 16
    // Multiples of the JPEG block size, so chroma does not bleed into the marker blocks.
    const val STORED_W = 64
    const val STORED_H = 32
  }
}
