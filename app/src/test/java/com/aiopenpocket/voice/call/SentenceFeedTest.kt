package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceFeedTest {

  @Test
  fun `a reply is read sentence by sentence as it is written, and the tail when it is saved`() {
    val feed = SentenceFeed()
    assertNull(feed.onStream("Hello"))
    assertEquals("Hello there.", feed.onStream("Hello there. How"))
    assertNull(feed.onStream("Hello there. How are"))
    assertEquals("How are you?", feed.onStream("Hello there. How are you? I"))
    assertEquals("I am fine.", feed.onFinal("Hello there. How are you? I am fine."))
  }

  @Test
  fun `a number with a point is not a sentence break`() {
    val feed = SentenceFeed()
    assertNull(feed.onStream("It costs 3.5 dollars"))
    assertEquals("It costs 3.5 dollars. Cheap.", feed.onFinal("It costs 3.5 dollars. Cheap."))
  }

  @Test
  fun `after a tool call the next iteration is read from its own start`() {
    val feed = SentenceFeed()
    assertEquals("Let me check.", feed.onStream("Let me check. "))
    // The saved reply is the last iteration's text only: it must be read whole, not skipped as "already read".
    assertNull(feed.onStream("It is 15 degrees"))
    assertEquals("It is 15 degrees in Belgrade.", feed.onFinal("It is 15 degrees in Belgrade."))
  }

  @Test
  fun `a saved reply that differs from what was read is not read again`() {
    val feed = SentenceFeed()
    assertEquals("Sure.", feed.onStream("Sure. Let"))
    assertNull(feed.onFinal("Something else entirely."))
  }
}
