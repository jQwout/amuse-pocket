package com.aiopenpocket.ui.scheduledworks

// @trace background-tasks-15a34a

import androidx.work.WorkInfo
import com.aiopenpocket.agent.tools.WorkManagerTool

data class ScheduledWorkItem(
  /** The job's UUID in WorkManager: a stable list key and a fallback cancel path. */
  val id: String,
  /** The unique name from the `id:` tag — work_manager schedules and cancels the job by it. */
  val uniqueName: String?,
  val title: String,
  val message: String,
  val state: WorkInfo.State,
  val type: String,
  /** null — there is no next run time (the job is already running or is blocked). */
  val nextScheduleTimeMillis: Long?,
)

/**
 * A screen row from [WorkInfo] — or null if the job was not scheduled by the work_manager tool. WorkManager also
 * holds other jobs (HeartbeatScheduler reminders, system workers): they lack our tags, and on screen they looked
 * like "Untitled" with an "unknown" status and a button that cancelled nothing. Reminders have their own card
 * with "Cancel" in the chat.
 */
internal fun WorkInfo.toScheduledWorkItem(): ScheduledWorkItem? {
  val type = when {
    WorkManagerTool.TAG_PERIODIC in tags -> WorkManagerTool.TAG_PERIODIC
    WorkManagerTool.TAG_ONE_TIME in tags -> WorkManagerTool.TAG_ONE_TIME
    else -> return null
  }
  fun tagValue(prefix: String) = tags.find { it.startsWith(prefix) }?.removePrefix(prefix)
  return ScheduledWorkItem(
    id = id.toString(),
    // WorkManager also adds an automatic tag with the worker's class name, so we search by the explicit prefix.
    uniqueName = tagValue(WorkManagerTool.TAG_ID_PREFIX),
    title = tagValue(WorkManagerTool.TAG_TITLE_PREFIX).orEmpty(),
    message = tagValue(WorkManagerTool.TAG_MESSAGE_PREFIX).orEmpty(),
    state = state,
    type = type,
    // Long.MAX_VALUE is how WorkManager says "not applicable"; it must not be formatted as a date.
    nextScheduleTimeMillis = nextScheduleTimeMillis.takeIf { it in 1 until Long.MAX_VALUE },
  )
}

/** The job's state in human words — the raw enum name is not shown to the user. */
internal fun WorkInfo.State.label(): String = when (this) {
  WorkInfo.State.ENQUEUED -> "Ждёт"
  WorkInfo.State.RUNNING -> "Выполняется"
  WorkInfo.State.BLOCKED -> "Заблокирована"
  WorkInfo.State.SUCCEEDED -> "Выполнена"
  WorkInfo.State.FAILED -> "Ошибка"
  WorkInfo.State.CANCELLED -> "Отменена"
}
