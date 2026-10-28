package com.aiopenpocket.agent.tools

// @trace english-ui-on-the-demo-path-223241
// @trace mcp-tools-f77d9b

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.i18n.AppLanguage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The line a tool draws in the notification shade when the app is in the background. On the English
 * demo path it was Cyrillic — «Черновик письма готов», «Будильник на 07:30» — because these literals
 * reach `setContentTitle`/`setContentText` through [launchExternalActivity] and never went through
 * [com.aiopenpocket.i18n.tr].
 *
 * The other half of each test is the boundary itself: the [ToolResult] text in the very same call is
 * addressed to the MODEL (AGENTS.md) and must stay Russian, so translating "everything in the file"
 * is a bug too, not extra care.
 */
// application = Application::class on purpose: the real AiOpenPocketApp mirrors the stored language
// into AppLanguage from a background collector (mirrorInterfaceLanguage), and that write landed in the
// middle of a test and put the interface back to Russian under it. A bare Application has no such
// collector, and these tools need nothing from the DI graph.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ToolNotificationTextTest {

  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  }

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun notifications() =
    shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

  /** Both tools are driven with the app in the background — that is the only path that posts a notification. */
  private val background = { false }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { (k, v) -> k to (JsonPrimitive(v) as JsonElement) }

  @Test
  fun `the alarm notification is English in an English interface`() {
    AppLanguage.code = AppLanguage.EN
    val result = runBlocking {
      AlarmTool(context, background).execute(args("action" to "set_alarm", "hour" to "7", "minute" to "30"))
    }
    val shade = shadowOf(notifications().single())
    assertEquals("Alarm for 07:30", shade.contentTitle.toString())
    assertEquals("Tap to set it in the Clock app.", shade.contentText.toString())
    // ... while the report back to the model stays Russian on purpose.
    assertTrue(CYRILLIC.containsMatchIn((result as ToolResult.Success).data))
  }

  @Test
  fun `the alarm list notification is English in an English interface`() {
    AppLanguage.code = AppLanguage.EN
    runBlocking { AlarmTool(context, background).execute(args("action" to "show_alarms")) }
    val shade = shadowOf(notifications().single())
    assertEquals("Alarms", shade.contentTitle.toString())
    assertFalse("Russian in the shade: ${shade.contentText}", CYRILLIC.containsMatchIn(shade.contentText.toString()))
  }

  @Test
  fun `the email draft notification is English in an English interface, subject and all`() {
    AppLanguage.code = AppLanguage.EN
    val result = runBlocking {
      EmailTool(context, background).execute(
        args("action" to "compose", "to" to "masha@example.com", "subject" to "Meeting notes", "body" to "Hi Masha"),
      )
    }
    val shade = shadowOf(notifications().single())
    assertEquals("Your draft is ready", shade.contentTitle.toString())
    assertEquals("“Meeting notes” — tap to open it in your mail app.", shade.contentText.toString())
    assertTrue(CYRILLIC.containsMatchIn((result as ToolResult.Success).data))
  }

  /** No subject — the other branch of the same text, which an English reader also reaches. */
  @Test
  fun `the email notification without a subject is English too`() {
    AppLanguage.code = AppLanguage.EN
    runBlocking { EmailTool(context, background).execute(args("action" to "compose", "to" to "masha@example.com")) }
    val shade = shadowOf(notifications().single())
    assertEquals("Tap to open it in your mail app.", shade.contentText.toString())
  }

  /** The Russian side is still there: a one-sided move to tr() would make the Russian app English. */
  @Test
  fun `the same notifications are Russian in the Russian interface`() {
    AppLanguage.code = AppLanguage.RU
    runBlocking { AlarmTool(context, background).execute(args("action" to "set_alarm", "hour" to "7", "minute" to "30")) }
    val shade = shadowOf(notifications().single())
    assertEquals("Будильник на 07:30", shade.contentTitle.toString())
    assertTrue(CYRILLIC.containsMatchIn(shade.contentText.toString()))
  }

  private companion object {
    val CYRILLIC = Regex("[\\u0400-\\u04FF]")
  }
}
