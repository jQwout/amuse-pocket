package com.aiopenpocket.agent.tools

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android does not allow startActivity from the background — the tool must not report "opened" when
 * nothing actually opened: in the background a notification with the same intent goes out instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalActivityLauncherTest {

  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun notifications() =
    shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

  @Test
  fun `in foreground the activity is started right away`() {
    val outcome = launchExternalActivity(context, { true }, Intent(AlarmClock.ACTION_SHOW_ALARMS), "T", "X")

    assertEquals(LaunchOutcome.Opened, outcome)
    assertEquals(AlarmClock.ACTION_SHOW_ALARMS, shadowOf(context).nextStartedActivity?.action)
    assertTrue(notifications().isEmpty())
  }

  @Test
  fun `in background a notification is posted instead of starting the activity`() {
    val outcome = launchExternalActivity(context, { false }, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Будильники", "Нажми")

    assertEquals(LaunchOutcome.Notified, outcome)
    assertNull(shadowOf(context).nextStartedActivity)
    assertEquals(1, notifications().size)
  }

  @Test
  fun `in background without notification permission nothing happens and it says so`() {
    shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

    val outcome = launchExternalActivity(context, { false }, Intent(AlarmClock.ACTION_SHOW_ALARMS), "T", "X")

    assertEquals(LaunchOutcome.NotNotified, outcome)
    assertNull(shadowOf(context).nextStartedActivity)
  }
}
