package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aiopenpocket.agent.core.notification.showAgentNotification
import com.aiopenpocket.agent.loop.ForegroundState

/** How an attempt to open another app ended — see [launchExternalActivity]. */
internal sealed interface LaunchOutcome {
  /** The app is on screen — the activity was opened right away. */
  data object Opened : LaunchOutcome

  /** The app is in the background — a notification is shown, tapping it opens the same thing. The action is NOT done yet. */
  data object Notified : LaunchOutcome

  /** The app is in the background and the notification cannot be shown (no permission) — nothing happened. */
  data object NotNotified : LaunchOutcome

  /** On screen, but opening failed (no suitable app, etc.). */
  data class Failed(val reason: String?) : LaunchOutcome
}

/**
 * Opens another app's activity (Clock, mail) from a tool, taking the background into account. An agent
 * turn may end when the user has already left the app (a background turn, a heartbeat), and since
 * Android 10 the system silently blocks starting activities from the background: startActivity does
 * not throw, the tool reported "alarm opened", and nothing appeared on screen. So off screen we show a
 * notification with the same intent: tapping it is an allowed launch path. The calling tool must tell
 * the model honestly that the action is waiting for a tap.
 */
internal fun launchExternalActivity(
  context: Context,
  foreground: ForegroundState,
  intent: Intent,
  notificationTitle: String,
  notificationText: String,
): LaunchOutcome {
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  if (foreground.isInForeground()) {
    return try {
      context.startActivity(intent)
      LaunchOutcome.Opened
    } catch (e: Exception) {
      LaunchOutcome.Failed(e.message)
    }
  }
  // The same code for both the PendingIntent and the notification id: repeating the same action replaces
  // the previous notification, while different actions (an alarm for 7:00 and for 8:00) do not overwrite
  // each other.
  val requestCode = intent.toUri(0).hashCode()
  val contentIntent = PendingIntent.getActivity(
    context,
    requestCode,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
  val shown = showAgentNotification(context, notificationTitle, notificationText, requestCode, contentIntent)
  return if (shown) LaunchOutcome.Notified else LaunchOutcome.NotNotified
}
