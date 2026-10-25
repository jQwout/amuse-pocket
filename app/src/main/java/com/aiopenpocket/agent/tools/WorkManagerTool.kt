package com.aiopenpocket.agent.tools

// @trace background-tasks-15a34a
// @trace user-agent-builder-848153

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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class WorkManagerTool(
  private val context: Context,
) : Tool {

  override val riskClass = RiskClass.WRITE

  // Per-call risk (Task 9): both schedule_* actions only create a new WorkManager job;
  // cancel_work is the irreversible one (the pending job is gone).
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "cancel_work" -> RiskClass.DESTRUCTIVE
    else -> RiskClass.WRITE
  }

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
    string("work_id", "Work ID, который вернул этот же тул при планировании (cancel_work); чужие задачи и напоминания он не отменяет")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    val workManager = WorkManager.getInstance(context)

    return when (action) {
      "schedule_notification" -> {
        val delay = arguments.long("delay_minutes") ?: 0L
        val (title, message, id) = buildNotificationFields(arguments, defaultTitle = "Напоминание")
        val request = OneTimeWorkRequestBuilder<NotificationWorker>()
          .setInitialDelay(delay, TimeUnit.MINUTES)
          .applyNotificationFields(title, message, id, kindTag = TAG_ONE_TIME)
          .build()
        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        ToolResult.Success("Scheduled one-time notification in $delay minutes. Work ID: $id")
      }

      "schedule_periodic" -> {
        val interval = arguments.long("interval_minutes") ?: 15L
        if (interval < 15) {
          return ToolResult.Error("Periodic work interval must be at least 15 minutes (Android WorkManager limit).")
        }
        val (title, message, id) = buildNotificationFields(arguments, defaultTitle = "Регулярная проверка")
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(interval, TimeUnit.MINUTES)
          .applyNotificationFields(title, message, id, kindTag = TAG_PERIODIC)
          .build()
        workManager.enqueueUniquePeriodicWork(id, ExistingPeriodicWorkPolicy.UPDATE, request)
        ToolResult.Success("Scheduled periodic work every $interval minutes. Work ID: $id")
      }

      "cancel_work" -> {
        val workId = arguments.string("work_id") ?: return missingArg("work_id")
        // Отменяем только СВОИ задачи. cancelUniqueWork принимает любое уникальное имя, и раньше
        // модель могла снести так чужую работу — напоминание `heartbeat_<id>` в обход HeartbeatTool
        // (строка в Room осталась бы «scheduled», а пуш не пришёл бы никогда), шаг плана или
        // служебный воркер. Свою задачу тул узнаёт по тегу `id:<work_id>`, который ставит при создании.
        val infos = workManager.getWorkInfosForUniqueWorkFlow(workId).first()
        val ownTag = "$TAG_ID_PREFIX$workId"
        if (infos.isEmpty() || infos.any { ownTag !in it.tags }) {
          return ToolResult.Error(
            "No task with ID '$workId' was scheduled by work_manager. It can only cancel its own tasks " +
              "(the 'Work ID' it returned); reminders are cancelled via the heartbeat tool.",
          )
        }
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
    .addTag("$TAG_TITLE_PREFIX$title")
    .addTag("$TAG_MESSAGE_PREFIX$message")
    .addTag("$TAG_ID_PREFIX$id")

  /**
   * Теги, которыми тул метит свои задачи. Их читает экран «Запланированные задачи»: по виду
   * ([TAG_ONE_TIME]/[TAG_PERIODIC]) он отличает наши задачи от чужих (напоминания
   * HeartbeatScheduler, служебные воркеры), а по [TAG_ID_PREFIX] — отменяет по уникальному имени.
   */
  companion object {
    const val TAG_ONE_TIME = "one_time"
    const val TAG_PERIODIC = "periodic"
    const val TAG_TITLE_PREFIX = "title:"
    const val TAG_MESSAGE_PREFIX = "msg:"
    const val TAG_ID_PREFIX = "id:"
  }
}
