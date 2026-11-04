package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.aiopenpocket.agent.tools.HeartbeatTool

/**
 * The on-time trigger of a one-time heartbeat ("remind me at 19:00").
 *
 * WorkManager alone was late: a delayed job waits for a Doze maintenance window, so on a phone lying still
 * a reminder could come an hour or more after its time. An alarm set with `setExactAndAllowWhileIdle`
 * fires in Doze too; [HeartbeatAlarmReceiver] then replaces the delayed WorkManager job with one that runs
 * right away (expedited: a while-idle alarm gives the app a short window to start work). The delayed job
 * stays armed until then as the fallback - alarms do not survive a reboot, WorkManager does - and the
 * unique work name means only one of the two ever runs.
 *
 * Exact alarms need USE_EXACT_ALARM (granted on install for a reminder app, API 33+) or the user's
 * SCHEDULE_EXACT_ALARM; without either, `setAndAllowWhileIdle` still fires in Doze, only less precisely.
 */
object HeartbeatAlarm {
  private const val TAG = "HeartbeatAlarm"
  internal const val ACTION = "com.aiopenpocket.HEARTBEAT_ALARM"

  fun arm(context: Context, heartbeatId: String, fireAt: Long) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    val pending = pendingIntent(context, heartbeatId)
    runCatching {
      val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
      if (exact) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
      } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
      }
      Log.d(TAG, "armed $heartbeatId exact=$exact in ${(fireAt - System.currentTimeMillis()) / 1000}s")
    }.onFailure { Log.w(TAG, "could not arm $heartbeatId: ${it.message}") }
  }

  fun cancel(context: Context, heartbeatId: String) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    alarms.cancel(pendingIntent(context, heartbeatId))
  }

  private fun pendingIntent(context: Context, heartbeatId: String): PendingIntent {
    val intent = Intent(context, HeartbeatAlarmReceiver::class.java)
      .setAction(ACTION)
      // The id in the data URI makes each heartbeat's PendingIntent distinct (extras alone do not).
      .setData(android.net.Uri.parse("heartbeat://$heartbeatId"))
      .putExtra(HeartbeatWorker.KEY_HEARTBEAT_ID, heartbeatId)
    return PendingIntent.getBroadcast(
      context,
      heartbeatId.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}

/** Runs the heartbeat now: replaces its delayed WorkManager job with an immediate, expedited one. */
class HeartbeatAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val heartbeatId = intent.getStringExtra(HeartbeatWorker.KEY_HEARTBEAT_ID) ?: return
    Log.d("HeartbeatAlarm", "fired $heartbeatId")
    val builder = OneTimeWorkRequestBuilder<HeartbeatWorker>()
      .setInputData(Data.Builder().putString(HeartbeatWorker.KEY_HEARTBEAT_ID, heartbeatId).build())
    // Expedited only on Android 12+: below that WorkManager runs expedited work as a foreground service and
    // needs getForegroundInfo, which HeartbeatWorker does not provide. Without the flag it still starts at once.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
    val request = builder.build()
    WorkManager.getInstance(context)
      .enqueueUniqueWork(HeartbeatTool.workName(heartbeatId), ExistingWorkPolicy.REPLACE, request)
  }
}
