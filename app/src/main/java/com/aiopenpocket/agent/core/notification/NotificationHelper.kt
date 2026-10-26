package com.aiopenpocket.agent.core.notification

// @trace mcp-tools-f77d9b

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aiopenpocket.R
import com.aiopenpocket.i18n.tr

private const val CHANNEL_ID = "ai_agent_channel"

/**
 * The single point for showing local notifications — shared by [com.aiopenpocket.agent.tools.NotificationsTool]
 * (immediate display) and [com.aiopenpocket.agent.workers.NotificationWorker] (delayed/periodic via
 * WorkManager), so the notification channel/style does not diverge between two places. Returns false if
 * the POST_NOTIFICATIONS permission is not granted (Android 13+) — the notification is not shown.
 */
fun showAgentNotification(
  context: Context,
  title: String,
  message: String,
  notificationId: Int,
  contentIntent: PendingIntent? = null,
): Boolean {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
    PackageManager.PERMISSION_GRANTED
  ) {
    return false
  }

  val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    // The user sees the channel name in the system notification settings — in the interface language. The
    // same CHANNEL_ID: calling createNotificationChannel again simply renames the existing channel.
    notificationManager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, tr("Напоминания и ответы агента", "Reminders and agent replies"), NotificationManager.IMPORTANCE_DEFAULT),
    )
  }

  val notification = NotificationCompat.Builder(context, CHANNEL_ID)
    // Our own monochrome icon instead of the system "i": in the shade it is clear at once who the notification is from.
    .setSmallIcon(R.drawable.ic_stat_agent)
    .setContentTitle(title)
    .setContentText(message)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setAutoCancel(true)
    .apply { if (contentIntent != null) setContentIntent(contentIntent) }
    .build()

  notificationManager.notify(notificationId, notification)
  return true
}
