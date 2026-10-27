package com.aiopenpocket.ui.components

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownScaleTest {

  @Test
  fun `scale of 1 returns the same style untouched`() {
    val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    assertEquals(base, base.scaledBy(1f))
  }

  @Test
  fun `fontSize and lineHeight are multiplied by the scale`() {
    val scaled = TextStyle(fontSize = 14.sp, lineHeight = 20.sp).scaledBy(1.5f)
    assertEquals(21f, scaled.fontSize.value, 0.001f)
    assertEquals(30f, scaled.lineHeight.value, 0.001f)
  }

  @Test
  fun `unspecified lineHeight stays unspecified`() {
    val scaled = TextStyle(fontSize = 14.sp).scaledBy(1.3f)
    assertEquals(18.2f, scaled.fontSize.value, 0.001f)
    assertEquals(TextUnit.Unspecified, scaled.lineHeight)
  }

  @Test
  fun `em-relative lineHeight is left as-is, not multiplied`() {
    // lineHeight in em scales automatically together with fontSize — it must not be multiplied a second time.
    val base = TextStyle(fontSize = 14.sp, lineHeight = 1.4.em)
    val scaled = base.scaledBy(1.3f)
    assertEquals(18.2f, scaled.fontSize.value, 0.001f)
    assertEquals(1.4f, scaled.lineHeight.value, 0.001f)
  }

  @Test
  fun `Typography scaledBy multiplies every text style`() {
    val typo = Typography(
      bodySmall = TextStyle(fontSize = 14.sp),
      displayLarge = TextStyle(fontSize = 57.sp),
    )
    val scaled = typo.scaledBy(1.5f)
    assertEquals(21f, scaled.bodySmall.fontSize.value, 0.001f)
    assertEquals(85.5f, scaled.displayLarge.fontSize.value, 0.001f)
  }

  @Test
  fun `Typography scaledBy of 1 returns the same instance`() {
    val typo = Typography()
    assertSame(typo, typo.scaledBy(1f))
  }
}
