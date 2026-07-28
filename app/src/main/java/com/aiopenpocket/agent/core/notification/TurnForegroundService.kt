package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.aiopenpocket.MainActivity

/**
 * Держит процесс живым, пока идёт ход агента (спека
 * `docs/superpowers/specs/2026-07-28-turn-foreground-service-design.md`). Поднимается и гасится
 * только из [AndroidTurnLifeSupport] — снаружи стартовать его нечем и незачем.
 *
 * Тип `shortService`: система даёт около трёх минут и затем зовёт [onTimeout], после чего сервис
 * ОБЯЗАН остановиться, иначе приложение будет прибито. Поэтому [handleTimeout] сначала сообщает
 * наверх (через [onSystemTimeout]), а потом безусловно зовёт `stopSelf`.
 *
 * Связь через статический колбэк, а не через DI-граф: экземпляр сервиса создаёт система, и тянуть в
 * него `AppGraph` ради одного уведомления наверх было бы дороже, чем один `@Volatile`-указатель,
 * который выставляет singleton-владелец сервиса.
 */
class TurnForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForeground обязан случиться в первые ~5 секунд после startForegroundService, иначе
    // система прибьёт приложение — поэтому никакой отложенной инициализации здесь нет.
    startForeground(NOTIFICATION_ID, buildNotification())
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
        // IMPORTANCE_LOW: без звука и вибрации. Отдельный канал от ai_agent_channel, чтобы юзер мог
        // приглушить «агент отвечает…», не глуша при этом напоминалки.
        NotificationChannel(CHANNEL_ID, "Ход агента", NotificationManager.IMPORTANCE_LOW),
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
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Агент отвечает…")
      .setContentText("Ответ придёт, даже если свернуть приложение.")
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      // Намеренно НЕ FOREGROUND_SERVICE_IMMEDIATE: поведение по умолчанию позволяет системе
      // отложить показ примерно на 10 секунд, и типичный ход завершится раньше, чем уведомление
      // вообще появится. Это то, что делает цену фичи почти нулевой.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "turn_foreground_channel"
    private const val NOTIFICATION_ID = 4242

    /** Выставляется [AndroidTurnLifeSupport] при сборке графа; дёргается из [handleTimeout]. */
    @Volatile
    var onSystemTimeout: (() -> Unit)? = null
  }
}
