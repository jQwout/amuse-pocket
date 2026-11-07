package com.aiopenpocket.voice.call

// @trace background-voice-calls-a-picture-in-picture-window-and-a-call-that-goes-on-off-screen-3ab748

import android.app.NotificationManager
import com.aiopenpocket.agent.core.notification.AndroidTurnLifeSupport
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The parts of a background call that live outside the call screen: the notification's and the mini
 * window's buttons reaching the call, the mute button's words, leaving the background cleanly, and the
 * rule that a call does not hear off screen when its notification could not be seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallBackgroundTest {

  private val app = RuntimeEnvironment.getApplication()

  @After
  fun tearDown() {
    CallBackground.pipHost = null
    CallBackground.serviceRunning = false
    CallForegroundService.leaveBackground(app)
  }

  @Test
  fun `each button's intent reaches the call as its own control`() = runTest {
    for (control in CallControl.entries) {
      val received = async(start = CoroutineStart.UNDISPATCHED) { CallBackground.controls.first() }
      val intent = shadowOf(CallBackground.controlIntent(app, control)).savedIntent

      CallControlReceiver().onReceive(app, intent)

      assertEquals(control, received.await())
      assertEquals(CallControlReceiver::class.java.name, intent.component?.className)
    }
  }

  @Test
  fun `the mute button says listen, unmute or mute`() {
    assertEquals(tr("Слушать", "Listen"), CallForegroundService.muteLabel(CallStatus(muted = true, paused = true)))
    assertEquals(tr("Включить микрофон", "Unmute"), CallForegroundService.muteLabel(CallStatus(muted = true)))
    assertEquals(tr("Без звука", "Mute"), CallForegroundService.muteLabel(CallStatus()))
  }

  @Test
  fun `the mini window's settings follow every change at once, not a dispatch later`() {
    var refreshes = 0
    CallBackground.pipHost = { refreshes++ }

    CallBackground.setOnCall(true)
    CallBackground.setHoldPip(true)
    CallBackground.setStatus(CallStatus(title = "Water", phase = "Listening"))
    val beforeMute = refreshes
    CallBackground.setStatus(CallStatus(title = "Water", phase = "Speaking"))
    assertEquals("a phase change does not touch the window's buttons", beforeMute, refreshes)
    CallBackground.setStatus(CallStatus(title = "Water", phase = "Speaking", muted = true))

    assertEquals(beforeMute + 1, refreshes)
    assertEquals("the call starting and the hold each re-applied the window", 2, beforeMute)
  }

  @Test
  fun `leaving the background clears the window, the hold and the status`() {
    CallBackground.setOnCall(true)
    CallBackground.setHoldPip(true)
    CallBackground.setStatus(CallStatus(title = "Water", muted = true))

    CallForegroundService.leaveBackground(app)
    CallForegroundService.leaveBackground(app)

    assertFalse(CallBackground.onCall.value)
    assertFalse(CallBackground.holdPip.value)
    assertEquals(CallStatus(), CallBackground.status.value)
  }

  @Test
  fun `a call does not go on off screen when its notification could not be seen`() {
    val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))

    notifications.setNotificationsEnabled(true)
    assertTrue(CallForegroundService.canShowNotification(app))

    notifications.setNotificationsEnabled(false)
    assertFalse(CallForegroundService.canShowNotification(app))
  }

  @Test
  fun `a turn during a background call starts no second service`() = runTest {
    CallBackground.serviceRunning = true
    val support = AndroidTurnLifeSupport(app)

    assertTrue(support.acquire("conv-1"))

    assertNull("the call's own service is enough", shadowOf(app).nextStartedService)
    support.release("conv-1")
  }
}
