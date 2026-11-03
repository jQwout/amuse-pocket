package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLoopTest {

  @Test
  fun `a pause between two sentences keeps the floor with the agent`() {
    val loop = CallLoop().userSaid().speaking(true).speaking(false)
    // The turn is still writing: listening now would cut in, and the next sentence would be read into the mic.
    assertEquals(CallPhase.THINKING, loop.phase)
    assertFalse(loop.wantsMic)
  }

  @Test
  fun `the floor comes back only when the turn is over and the voice is quiet`() {
    val speakingTail = CallLoop().userSaid().speaking(true).turnEnded()
    assertEquals(CallPhase.SPEAKING, speakingTail.phase)
    assertFalse(speakingTail.wantsMic)

    val done = speakingTail.speaking(false)
    assertEquals(CallPhase.LISTENING, done.phase)
    assertTrue(done.wantsMic)
  }

  @Test
  fun `cutting the agent off gives the floor back while the turn still runs`() {
    val loop = CallLoop().userSaid().speaking(true).cutOff()
    assertEquals(CallPhase.LISTENING, loop.phase)
    assertTrue(loop.wantsMic)
    assertFalse(loop.readsReply)
    // The next phrase is a fresh reply, read again.
    assertTrue(loop.userSaid().readsReply)
  }

  @Test
  fun `an approval card holds the microphone`() {
    val loop = CallLoop().userSaid().approval(true)
    assertEquals(CallPhase.WAITING_APPROVAL, loop.phase)
    assertFalse(loop.wantsMic)
  }
}
