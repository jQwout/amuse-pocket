package com.aiopenpocket.agent.tools

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.workers.NotificationWorker
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class WorkManagerTool(
  private val context: Context,
) : Tool {

  override val name = "work_manager"
  override val description =
    "Планирует фоновые задачи через WorkManager: одноразовые уведомления через N минут " +
    "и периодические проверки/уведомления."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("schedule_notification,schedule_periodic,cancel_work"),
            "description" to JsonPrimitive("Действие: schedule_notification, schedule_periodic, cancel_work"),
          )),
          "delay_minutes" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Задержка в минутах (для schedule_notification)"),
          )),
          "interval_minutes" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Интервал в минутах (для schedule_periodic, минимум 15)"),
          )),
          "title" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Заголовок уведомления"),
          )),
          "message" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Текст уведомления"),
          )),
          "work_id" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("ID задачи для отмены (cancel_work)"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'action'")
    val workManager = WorkManager.getInstance(context)

    return when (action) {
      "schedule_notification" -> {
        val delay = (arguments["delay_minutes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val title = (arguments["title"] as? JsonPrimitive)?.content ?: "Reminder"
        val message = (arguments["message"] as? JsonPrimitive)?.content ?: ""
        val id = UUID.randomUUID().toString()

        val data = Data.Builder()
          .putString(NotificationWorker.KEY_TITLE, title)
          .putString(NotificationWorker.KEY_MESSAGE, message)
          .putInt(NotificationWorker.KEY_NOTIFICATION_ID, id.hashCode())
          .build()

        val request = OneTimeWorkRequestBuilder<NotificationWorker>()
          .setInitialDelay(delay, TimeUnit.MINUTES)
          .setInputData(data)
          .addTag("one_time")
          .addTag("title:$title")
          .addTag("msg:$message")
          .addTag("id:$id")
          .build()

        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        ToolResult.Success("Scheduled one-time notification in $delay minutes. Work ID: $id")
      }

      "schedule_periodic" -> {
        val interval = (arguments["interval_minutes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 15L
        if (interval < 15) {
          return ToolResult.Error("Periodic work interval must be at least 15 minutes (Android WorkManager limit).")
        }
        val title = (arguments["title"] as? JsonPrimitive)?.content ?: "Periodic Check"
        val message = (arguments["message"] as? JsonPrimitive)?.content ?: ""
        val id = UUID.randomUUID().toString()

        val data = Data.Builder()
          .putString(NotificationWorker.KEY_TITLE, title)
          .putString(NotificationWorker.KEY_MESSAGE, message)
          .putInt(NotificationWorker.KEY_NOTIFICATION_ID, id.hashCode())
          .build()

        val request = PeriodicWorkRequestBuilder<NotificationWorker>(interval, TimeUnit.MINUTES)
          .setInputData(data)
          .addTag("periodic")
          .addTag("title:$title")
          .addTag("msg:$message")
          .addTag("id:$id")
          .build()

        workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.UPDATE, request)
        ToolResult.Success("Scheduled periodic work every $interval minutes. Work ID: $id")
      }

      "cancel_work" -> {
        val workId = (arguments["work_id"] as? JsonPrimitive)?.content
          ?: return ToolResult.Error("Missing 'work_id'")
        workManager.cancelUniqueWork(workId)
        ToolResult.Success("Cancelled work: $workId")
      }

      else -> ToolResult.Error("Unknown action: $action")
    }
  }
}
