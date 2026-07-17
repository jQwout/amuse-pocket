package com.aiopenpocket.agent.core.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFontScaleTest {

  @Test
  fun `fromName resolves every stored preset by its own name`() {
    for (preset in ChatFontScale.entries) {
      assertEquals(preset, ChatFontScale.fromName(preset.name))
    }
  }

  @Test
  fun `fromName falls back to default for null, unknown and legacy values`() {
    assertEquals(ChatFontScale.DEFAULT, ChatFontScale.fromName(null))
    assertEquals(ChatFontScale.DEFAULT, ChatFontScale.fromName(""))
    assertEquals(ChatFontScale.DEFAULT, ChatFontScale.fromName("HUGE"))
    assertEquals(ChatFontScale.DEFAULT, ChatFontScale.fromName("medium")) // регистр значим
  }

  @Test
  fun `default is MEDIUM at scale 1`() {
    assertEquals(ChatFontScale.MEDIUM, ChatFontScale.DEFAULT)
    assertEquals(1f, ChatFontScale.MEDIUM.scale)
  }

  @Test
  fun `presets are strictly increasing so the picker order is monotonic`() {
    val scales = ChatFontScale.entries.map { it.scale }
    assertEquals(scales.sorted(), scales)
    assertEquals(scales.distinct(), scales)
  }
}
