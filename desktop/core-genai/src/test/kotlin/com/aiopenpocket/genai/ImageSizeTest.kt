package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Пропорции и размер кадра. Живёт в ядре, а не в экране, потому что это арифметика, которую
 * пользователь видит числом: у `:app-desktop` тестов нет вовсе, и ошибка здесь выглядела бы как
 * уверенно показанная неправда.
 */
class ImageSizeTest {

  @Test
  fun `ratio strings turn into numbers`() {
    assertEquals(1.0, aspectRatioValue("1:1"))
    assertEquals(16.0 / 9, aspectRatioValue("16:9")!!, 1e-9)
    assertEquals(2.39, aspectRatioValue("2.39:1")!!, 1e-9)
    assertEquals(9.0 / 16, aspectRatioValue("9:16")!!, 1e-9)
  }

  @Test
  fun `an unparseable ratio is null, not a guess`() {
    // `auto` реально приходит в списке у части моделей, и нарисовать под него квадрат значило бы
    // показать пропорцию, которой никто не обещал.
    assertNull(aspectRatioValue("auto"))
    assertNull(aspectRatioValue("16-9"))
    assertNull(aspectRatioValue("0:1"))
    assertNull(aspectRatioValue(""))
  }

  @Test
  fun `resolution tiers turn into the long side`() {
    assertEquals(1024, resolutionLongSide("1K"))
    assertEquals(2048, resolutionLongSide("2K"))
    assertEquals(4096, resolutionLongSide("4K"))
    assertEquals(512, resolutionLongSide("512"))
    assertEquals(2048, resolutionLongSide("2k"))
  }

  @Test
  fun `an unknown tier gives no number`() {
    assertNull(resolutionLongSide("huge"))
    assertNull(resolutionLongSide(""))
  }

  @Test
  fun `wide and tall frames put the tier on the long side`() {
    assertEquals(2048 to 856, estimatedPixels("2K", "2.39:1"))
    assertEquals(2048 to 1152, estimatedPixels("2K", "16:9"))
    assertEquals(2048 to 2048, estimatedPixels("2K", "1:1"))
    // Вертикальный кадр: длинная сторона — высота, а не ширина.
    assertEquals(1152 to 2048, estimatedPixels("2K", "9:16"))
  }

  @Test
  fun `half an answer is no answer`() {
    // Показать «2048 × ?» нельзя, а додумать вторую сторону — тем более.
    assertNull(estimatedPixels("2K", null))
    assertNull(estimatedPixels(null, "16:9"))
    assertNull(estimatedPixels("2K", "auto"))
    assertNull(estimatedPixels("огромное", "16:9"))
  }

  @Test
  fun `megapixels are rounded the same way regardless of system locale`() {
    // Точка, а не запятая: на русской локали «%.1f» иначе даёт «1,8 Mn».
    assertEquals("1.8 Mn", megapixels(2048 to 856))
    assertEquals("4.2 Mn", megapixels(2048 to 2048))
  }
}
