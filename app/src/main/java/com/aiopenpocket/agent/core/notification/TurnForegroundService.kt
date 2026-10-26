package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aiopenpocket.MainActivity
import com.aiopenpocket.R
import com.aiopenpocket.i18n.tr

/**
 * Keeps the process alive while an agent turn is running (spec
 * `docs/superpowers/specs/2026-07-28-turn-foreground-service-design.md`). It is started and stopped
 * only from [AndroidTurnLifeSupport] — there is nothing outside to start it with and no reason to.
 *
 * Type `shortService`: the system gives about three minutes and then calls [onTimeout], after which the
 * service MUST stop, or the app will be killed. So [handleTimeout] first reports upwards (through
 * [onSystemTimeout]) and then unconditionally calls `stopSelf`.
 *
 * The link is a static callback rather than the DI graph: the system creates the service instance, and
 * pulling `AppGraph` into it for the sake of one upward notification would cost more than one
 * `@Volatile` pointer that the service's singleton owner sets.
 */
class TurnForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForeground must happen within ~5 seconds of startForegroundService, or the system kills the
    // app — so there is no deferred initialization here.
    //
    // The type is passed EXPLICITLY rather than relying on the one declared in the manifest: the
    // two-argument form on API 34+ does take the types from the manifest, but shortService has special
    // semantics (a timeout, no combining), and the documentation asks for it to be specified explicitly.
    // Robolectric cannot check this — the explicit form costs nothing, while the cost of a mistake shows
    // only on a device with Android 14+.
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
      } else {
        0
      },
    )
    return START_NOT_STICKY
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  override fun onTimeout(startId: Int) = handleTimeout()

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

  private fun handleTimeout() {
    onSystemTimeout?.invoke()
    stopSelf()
  }

  private fun buildNotification(): Notification {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.createNotificationChannel(
        // IMPORTANCE_LOW: no sound or vibration. A channel separate from ai_agent_channel so the user can
        // mute "the agent is replying…" without muting reminders.
        NotificationChannel(CHANNEL_ID, tr("Ход агента", "Agent turn"), NotificationManager.IMPORTANCE_LOW),
      )
    }
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentIntent = PendingIntent.getActivity(
      this,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_agent)
      .setContentTitle(tr("Агент отвечает…", "The agent is replying…"))
      .setContentText(tr("Ответ придёт, даже если свернуть приложение.", "The reply will arrive even if you leave the app."))
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      // Deliberately NOT FOREGROUND_SERVICE_IMMEDIATE: the default behavior lets the system delay showing
      // it by about 10 seconds, and a typical turn finishes before the notification even appears. That is
      // what makes the cost of the feature almost zero.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "turn_foreground_channel"
    private const val NOTIFICATION_ID = 4242

    /** Set by [AndroidTurnLifeSupport] when the graph is built; called from [handleTimeout]. */
    @Volatile
    var onSystemTimeout: (() -> Unit)? = null
  }
}
