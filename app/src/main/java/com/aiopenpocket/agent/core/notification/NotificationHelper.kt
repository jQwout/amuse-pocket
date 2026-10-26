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
 * Единая точка показа локальных уведомлений — общая для [com.aiopenpocket.agent.tools.NotificationsTool]
 * (немедленный показ) и [com.aiopenpocket.agent.workers.NotificationWorker] (отложенный/периодический через
 * WorkManager), чтобы канал/стиль уведомления не расходились по двум местам. Возвращает false, если
 * разрешение POST_NOTIFICATIONS не выдано (Android 13+) — уведомление не показано.
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
    // Имя канала видит пользователь в системных настройках уведомлений — по-русски. Тот же
    // CHANNEL_ID: повторный createNotificationChannel просто переименует уже созданный канал.
    notificationManager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, tr("Напоминания и ответы агента", "Reminders and agent replies"), NotificationManager.IMPORTANCE_DEFAULT),
    )
  }

  val notification = NotificationCompat.Builder(context, CHANNEL_ID)
    // Своя монохромная иконка вместо системной «i»: в шторке сразу видно, от кого уведомление.
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
