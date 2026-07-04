package com.aiopenpocket.agent.workers

// @trace background-tasks-15a34a

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aiopenpocket.agent.core.notification.showAgentNotification

class NotificationWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {

  override fun doWork(): Result {
    val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
    val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()
    val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, System.currentTimeMillis().toInt())

    val shown = showAgentNotification(applicationContext, title, message, notificationId)
    // Разрешение могли отозвать между постановкой в очередь и срабатыванием — сообщаем об этом
    // через Result.failure(), а не молча делаем вид, что уведомление показано.
    return if (shown) Result.success() else Result.failure()
  }

  companion object {
    const val KEY_TITLE = "title"
    const val KEY_MESSAGE = "message"
    const val KEY_NOTIFICATION_ID = "notification_id"
  }
}
