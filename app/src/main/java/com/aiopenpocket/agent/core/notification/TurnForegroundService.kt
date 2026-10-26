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
    //
    // Тип передаём ЯВНО, а не полагаемся на объявленный в манифесте: двухаргументная форма на
    // API 34+ действительно берёт типы из манифеста, но у shortService особая семантика (таймаут,
    // запрет комбинирования), и документация просит указывать его явно. Проверить это в Robolectric
    // нечем — цена явной формы нулевая, цена ошибки видна только на устройстве с Android 14+.
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
        // IMPORTANCE_LOW: без звука и вибрации. Отдельный канал от ai_agent_channel, чтобы юзер мог
        // приглушить «агент отвечает…», не глуша при этом напоминалки.
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
