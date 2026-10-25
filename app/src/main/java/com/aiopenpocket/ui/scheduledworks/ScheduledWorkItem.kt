package com.aiopenpocket.ui.scheduledworks

// @trace background-tasks-15a34a

import androidx.work.WorkInfo
import com.aiopenpocket.agent.tools.WorkManagerTool

data class ScheduledWorkItem(
  /** UUID задачи в WorkManager: стабильный ключ списка и запасной путь отмены. */
  val id: String,
  /** Уникальное имя из тега `id:` — по нему work_manager ставит и отменяет задачу. */
  val uniqueName: String?,
  val title: String,
  val message: String,
  val state: WorkInfo.State,
  val type: String,
  /** null — времени следующего запуска нет (задача уже выполняется или заблокирована). */
  val nextScheduleTimeMillis: Long?,
)

/**
 * Строка экрана из [WorkInfo] — или null, если задачу поставил не тул work_manager.
 * WorkManager держит и чужие задачи (напоминания HeartbeatScheduler, служебные воркеры):
 * у них нет наших тегов, и на экране они выглядели как «Без названия» со статусом «unknown»
 * и кнопкой, которая ничего не отменяла. У напоминаний своя карточка с «Отменить» в чате.
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
    // WorkManager также добавляет авто-тег с именем класса воркера, поэтому ищем по явному префиксу.
    uniqueName = tagValue(WorkManagerTool.TAG_ID_PREFIX),
    title = tagValue(WorkManagerTool.TAG_TITLE_PREFIX).orEmpty(),
    message = tagValue(WorkManagerTool.TAG_MESSAGE_PREFIX).orEmpty(),
    state = state,
    type = type,
    // Long.MAX_VALUE — так WorkManager говорит «неприменимо»; форматировать его в дату нельзя.
    nextScheduleTimeMillis = nextScheduleTimeMillis.takeIf { it in 1 until Long.MAX_VALUE },
  )
}

/** Состояние задачи по-русски — сырое имя enum'а пользователю не показываем. */
internal fun WorkInfo.State.label(): String = when (this) {
  WorkInfo.State.ENQUEUED -> "Ждёт"
  WorkInfo.State.RUNNING -> "Выполняется"
  WorkInfo.State.BLOCKED -> "Заблокирована"
  WorkInfo.State.SUCCEEDED -> "Выполнена"
  WorkInfo.State.FAILED -> "Ошибка"
  WorkInfo.State.CANCELLED -> "Отменена"
}
