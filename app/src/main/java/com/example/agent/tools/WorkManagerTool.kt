package com.aiopenpocket.agent.tools

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.workers.NotificationWorker
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class WorkManagerTool(
  private val context: Context,
) : Tool {

  override val riskClass = RiskClass.WRITE

  override val name = "work_manager"
  override val description =
    "Планирует фоновые задачи через WorkManager: одноразовые уведомления через N минут " +
    "и периодические проверки/уведомления."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: schedule_notification, schedule_periodic, cancel_work",
      required = true,
      enum = listOf("schedule_notification", "schedule_periodic", "cancel_work"),
    )
    integer("delay_minutes", "Задержка в минутах (для schedule_notification)")
    integer("interval_minutes", "Интервал в минутах (для schedule_periodic, минимум 15)")
    string("title", "Заголовок уведомления")
    string("message", "Текст уведомления")
    string("work_id", "ID задачи для отмены (cancel_work)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    val workManager = WorkManager.getInstance(context)

    return when (action) {
      "schedule_notification" -> {
        val delay = arguments.long("delay_minutes") ?: 0L
        val (title, message, id) = buildNotificationFields(arguments, defaultTitle = "Reminder")
        val request = OneTimeWorkRequestBuilder<NotificationWorker>()
          .setInitialDelay(delay, TimeUnit.MINUTES)
          .applyNotificationFields(title, message, id, kindTag = "one_time")
          .build()
        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        ToolResult.Success("Scheduled one-time notification in $delay minutes. Work ID: $id")
      }

      "schedule_periodic" -> {
        val interval = arguments.long("interval_minutes") ?: 15L
        if (interval < 15) {
          return ToolResult.Error("Periodic work interval must be at least 15 minutes (Android WorkManager limit).")
        }
        val (title, message, id) = buildNotificationFields(arguments, defaultTitle = "Periodic Check")
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(interval, TimeUnit.MINUTES)
          .applyNotificationFields(title, message, id, kindTag = "periodic")
          .build()
        workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.UPDATE, request)
        ToolResult.Success("Scheduled periodic work every $interval minutes. Work ID: $id")
      }

      "cancel_work" -> {
        val workId = arguments.string("work_id") ?: return missingArg("work_id")
        workManager.cancelUniqueWork(workId)
        ToolResult.Success("Cancelled work: $workId")
      }

      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private data class NotificationFields(val title: String, val message: String, val id: String)

  private fun buildNotificationFields(
    args: Map<String, JsonElement>,
    defaultTitle: String,
  ): NotificationFields = NotificationFields(
    title = args.string("title") ?: defaultTitle,
    message = args.string("message").orEmpty(),
    id = UUID.randomUUID().toString(),
  )

  private fun <B : WorkRequest.Builder<B, *>> B.applyNotificationFields(
    title: String,
    message: String,
    id: String,
    kindTag: String,
  ): B = setInputData(
    Data.Builder()
      .putString(NotificationWorker.KEY_TITLE, title)
      .putString(NotificationWorker.KEY_MESSAGE, message)
      .putInt(NotificationWorker.KEY_NOTIFICATION_ID, id.hashCode())
      .build(),
  )
    .addTag(kindTag)
    .addTag("title:$title")
    .addTag("msg:$message")
    .addTag("id:$id")
}
