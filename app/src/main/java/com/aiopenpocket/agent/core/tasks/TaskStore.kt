package com.aiopenpocket.agent.core.tasks

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import android.app.Application
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class TaskPriority { LOW, NORMAL, HIGH }

@Serializable
data class TaskListInfo(val id: Long, val name: String)

@Serializable
data class TaskItem(
  val id: Long,
  val listId: Long,
  val title: String,
  val done: Boolean = false,
  /** Epoch millis of the due moment, or null. */
  val dueAt: Long? = null,
  val priority: TaskPriority? = null,
  val createdAt: Long,
)

@Serializable
data class TaskData(
  val lists: List<TaskListInfo> = listOf(TaskListInfo(INBOX_ID, DEFAULT_LIST_NAME)),
  val tasks: List<TaskItem> = emptyList(),
  val nextId: Long = 2,
)

const val INBOX_ID = 1L
const val DEFAULT_LIST_NAME = "Inbox"

/**
 * The task list shared by the Task list agent (through the `task_list` tool) and its native screen.
 *
 * A JSON file, not Room: a schema bump wipes the phone's data (destructive-migration policy), and the
 * agent's tool and the screen must read the very same records. The file is replaced atomically
 * (temp file, then rename) so a kill mid-write cannot leave half a list. Open for a JVM test, which
 * hands it a plain file instead of an Application.
 */
open class TaskBoard(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val mutex = Mutex()
  private val _data = MutableStateFlow(load())
  val data: StateFlow<TaskData> = _data.asStateFlow()

  private fun load(): TaskData = try {
    if (file.exists()) json.decodeFromString<TaskData>(file.readText()) else TaskData()
  } catch (_: Exception) {
    // A damaged file must not crash the app on open; the list starts over rather than wedge.
    TaskData()
  }

  /** The list called [name] (case-insensitive), created when it is new; a blank name means the Inbox. */
  private fun TaskData.withList(name: String?): Pair<TaskData, Long> {
    val clean = name?.trim().orEmpty()
    if (clean.isEmpty()) return this to INBOX_ID
    lists.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return this to it.id }
    val list = TaskListInfo(nextId, clean.take(MAX_NAME))
    return copy(lists = lists + list, nextId = nextId + 1) to list.id
  }

  private suspend fun mutate(block: (TaskData) -> TaskData) = mutex.withLock {
    val next = block(_data.value)
    if (next == _data.value) return@withLock
    withContext(Dispatchers.IO) {
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(json.encodeToString(next))
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    }
    _data.value = next
  }

  suspend fun addList(name: String): Long {
    var id = INBOX_ID
    mutate { d -> d.withList(name).also { id = it.second }.first }
    return id
  }

  suspend fun addTask(title: String, list: String? = null, dueAt: Long? = null, priority: TaskPriority? = null): TaskItem {
    lateinit var created: TaskItem
    mutate { d ->
      val (withList, listId) = d.withList(list)
      created = TaskItem(
        id = withList.nextId, listId = listId, title = title.trim().take(MAX_TITLE),
        dueAt = dueAt, priority = priority, createdAt = clock(),
      )
      withList.copy(tasks = withList.tasks + created, nextId = withList.nextId + 1)
    }
    return created
  }

  suspend fun setDone(id: Long, done: Boolean): Boolean = updateTask(id, done = done)

  /**
   * Changes the fields that are given; [clearDue] / [clearPriority] erase them (null alone means
   * "leave as is"). Returns false when no such task exists.
   */
  suspend fun updateTask(
    id: Long,
    title: String? = null,
    list: String? = null,
    dueAt: Long? = null,
    clearDue: Boolean = false,
    priority: TaskPriority? = null,
    clearPriority: Boolean = false,
    done: Boolean? = null,
  ): Boolean {
    var found = false
    mutate { d ->
      val old = d.tasks.firstOrNull { it.id == id } ?: return@mutate d
      found = true
      val (withList, listId) = if (list != null) d.withList(list) else d to old.listId
      val new = old.copy(
        title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE) ?: old.title,
        listId = listId,
        dueAt = if (clearDue) null else dueAt ?: old.dueAt,
        priority = if (clearPriority) null else priority ?: old.priority,
        done = done ?: old.done,
      )
      withList.copy(tasks = withList.tasks.map { if (it.id == id) new else it })
    }
    return found
  }

  suspend fun deleteTask(id: Long): Boolean {
    var found = false
    mutate { d ->
      if (d.tasks.none { it.id == id }) return@mutate d
      found = true
      d.copy(tasks = d.tasks.filterNot { it.id == id })
    }
    return found
  }

  /** Removes a list with its tasks; the Inbox cannot be removed. */
  suspend fun deleteList(id: Long): Boolean {
    if (id == INBOX_ID) return false
    var found = false
    mutate { d ->
      if (d.lists.none { it.id == id }) return@mutate d
      found = true
      d.copy(lists = d.lists.filterNot { it.id == id }, tasks = d.tasks.filterNot { it.listId == id })
    }
    return found
  }

  private companion object {
    const val MAX_TITLE = 300
    const val MAX_NAME = 40
  }
}

@Inject
@SingleIn(AppScope::class)
class TaskStore(application: Application) : TaskBoard(File(application.filesDir, "tasks.json"))
