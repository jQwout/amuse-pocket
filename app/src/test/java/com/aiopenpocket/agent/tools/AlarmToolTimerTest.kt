package com.aiopenpocket.agent.tools

// @trace a-timer-in-the-phone-s-clock-not-in-the-chat-cfafda

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.isCardResult
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.DrivenUiEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Set a timer" is the phone's own timer: started in Clock without opening it, and shown in the chat as a
 * card that says so - not the chat's own countdown, and not a reminder nobody can find again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AlarmToolTimerTest {

  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { (k, v) -> k to (JsonPrimitive(v) as JsonElement) }

  private fun tool(onScreen: Boolean) = AlarmTool(context) { onScreen }

  @Test
  fun `on screen the timer starts in Clock without opening it, and comes back as a chat card`() {
    val result = runBlocking {
      tool(onScreen = true).execute(args("action" to "set_timer", "minutes" to "3", "message" to "Tea"))
    }

    val intent = shadowOf(context).nextStartedActivity
    assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
    assertEquals(180, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
    assertEquals("Tea", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
    assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    assertTrue(
      "setting it must not count as leaving the app, or a call shrinks into its mini window",
      intent.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0,
    )

    val data = (result as ToolResult.Success).data
    assertTrue(isCardResult("alarm", data))
    val card = DrivenUiEngine.parseJsonString(data) as DrivenUiCard.TimerCard
    assertEquals("Tea", card.label)
    assertEquals(180, card.durationSec)
    assertTrue(card.setOnDevice)
  }

  @Test
  fun `in the background it is a notification and the model hears the timer is not set yet`() {
    val result = runBlocking { tool(onScreen = false).execute(args("action" to "set_timer", "minutes" to "1", "seconds" to "30")) }

    assertNull(shadowOf(context).nextStartedActivity)
    val data = (result as ToolResult.Success).data
    assertFalse("no card for a timer that does not run", isCardResult("alarm", data))
    assertTrue(data.contains("НЕ"))
    val shown = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications
    assertTrue(shadowOf(shown.single()).contentTitle.toString().contains("1:30"))
  }

  @Test
  fun `a length of nothing or over a day is refused`() {
    for (bad in listOf(args("action" to "set_timer"), args("action" to "set_timer", "minutes" to "1441"))) {
      assertTrue(runBlocking { tool(onScreen = true).execute(bad) } is ToolResult.Error)
    }
    assertNull(shadowOf(context).nextStartedActivity)
  }

  @Test
  fun `other alarm results stay text for the model`() {
    val result = runBlocking { tool(onScreen = true).execute(args("action" to "show_timers")) }

    assertEquals(AlarmClock.ACTION_SHOW_TIMERS, shadowOf(context).nextStartedActivity.action)
    assertFalse(isCardResult("alarm", (result as ToolResult.Success).data))
  }

  @Test
  fun `a drawn timer card is not on the device, and lengths read as Clock writes them`() {
    val drawn = DrivenUiEngine.parseJsonString("""{"template":"timer","params":{"label":"Eggs","duration_sec":300}}""")
    assertFalse((drawn as DrivenUiCard.TimerCard).setOnDevice)

    assertEquals("3:00", AlarmTool.formatLength(180))
    assertEquals("0:45", AlarmTool.formatLength(45))
    assertEquals("1:00:05", AlarmTool.formatLength(3605))
  }
}
