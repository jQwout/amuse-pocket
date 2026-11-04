package com.aiopenpocket.agent.tools

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce
// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.boolean
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.tasks.DEFAULT_LIST_NAME
import com.aiopenpocket.agent.core.tasks.INBOX_ID
import com.aiopenpocket.agent.core.tasks.TaskBoard
import com.aiopenpocket.agent.core.tasks.TaskItem
import com.aiopenpocket.agent.core.tasks.TaskPriority
import com.aiopenpocket.agent.core.tasks.TaskStore
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Task list agent's hands: the same store the Task list screen shows, so a task said in the chat
 * appears on the screen and a tick on the screen is seen by the agent. The agent keeps tasks here, not
 * in memory (memory is for facts about the person).
 *
 * Risk per call: add, update and complete change only the person's own list (WRITE); delete is
 * DESTRUCTIVE (no trash), so it asks in the chat and is forbidden in the background.
 */
@Inject
@SingleIn(AppScope::class)
class TaskListTool(store: TaskStore) : TaskListToolCore(store)

/** The tool itself, over a plain [TaskBoard] so a JVM test can drive it with a file in a temp folder. */
open class TaskListToolCore(private val board: TaskBoard) : Tool {

  // Task titles can echo text the person pasted from elsewhere; treat as untrusted like storage_tool.
  override val returnsUntrustedContent = true

  override val name = NAME
  override val description =
    "The person's task list, shared with the Task list app on their phone. Actions: add (title; optional " +
      "list, due as YYYY-MM-DD or YYYY-MM-DD HH:mm, priority low/normal/high), list (optional list name, " +
      "include_done), complete (id), update (id plus the fields to change; due or priority \"none\" clears " +
      "it), delete (id), lists (list names with counts). Tasks live here, not in memory."
  override val schema = toolSchema(name, description) {
    string(
      "action", "What to do", required = true,
      enum = listOf("add", "list", "complete", "update", "delete", "lists"),
    )
    string("title", "Task text (add; update to rename)")
    string("list", "List name, e.g. Shopping or Work. Default Inbox. A new name creates the list")
    string("due", "Due date YYYY-MM-DD or YYYY-MM-DD HH:mm local time; \"none\" clears it (update)")
    string("priority", "low, normal or high; \"none\" clears it (update)", enum = listOf("low", "normal", "high", "none"))
    integer("id", "Task id from the list (complete, update, delete)")
    boolean("include_done", "list: also show completed tasks")
    boolean("done", "update: set the done flag")
  }

  override val riskClass = RiskClass.DESTRUCTIVE

  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "add", "update", "complete" -> RiskClass.WRITE
    "delete" -> RiskClass.DESTRUCTIVE
    else -> RiskClass.READ
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    return when (val action = arguments.string("action") ?: return missingArg("action")) {
      "add" -> add(arguments)
      "list" -> list(arguments)
      "complete" -> complete(arguments)
      "update" -> update(arguments)
      "delete" -> delete(arguments)
      "lists" -> lists()
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun add(args: Map<String, JsonElement>): ToolResult {
    val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return missingArg("title")
    val due = args.string("due")?.let { parseDue(it) ?: return badDue(it) }
    val priority = args.string("priority")?.let { parsePriority(it) ?: return badPriority(it) }
    val task = board.addTask(title, args.string("list"), due, priority)
    return ToolResult.Success("Added task #${task.id}: ${describe(task)}")
  }

  private fun list(args: Map<String, JsonElement>): ToolResult {
    val data = board.data.value
    val listName = args.string("list")?.trim()
    val listId = listName?.let { n ->
      data.lists.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id
        ?: return ToolResult.Success("No list called '$n'.")
    }
    val includeDone = args.boolean("include_done") == true
    val tasks = data.tasks.filter { (listId == null || it.listId == listId) && (includeDone || !it.done) }
    if (tasks.isEmpty()) return ToolResult.Success("No tasks.")
    val names = data.lists.associate { it.id to it.name }
    return ToolResult.Success(
      tasks.joinToString("\n") { "#${it.id} [${names[it.listId] ?: DEFAULT_LIST_NAME}] ${describe(it)}" },
    )
  }

  private suspend fun complete(args: Map<String, JsonElement>): ToolResult {
    val id = args.long("id") ?: return missingArg("id")
    return if (board.setDone(id, true)) ToolResult.Success("Task #$id done.") else notFound(id)
  }

  private suspend fun update(args: Map<String, JsonElement>): ToolResult {
    val id = args.long("id") ?: return missingArg("id")
    val dueRaw = args.string("due")
    val prioRaw = args.string("priority")
    val clearDue = dueRaw.equals("none", ignoreCase = true)
    val clearPrio = prioRaw.equals("none", ignoreCase = true)
    val due = if (clearDue || dueRaw == null) null else parseDue(dueRaw) ?: return badDue(dueRaw)
    val priority = if (clearPrio || prioRaw == null) null else parsePriority(prioRaw) ?: return badPriority(prioRaw)
    val ok = board.updateTask(
      id = id, title = args.string("title"), list = args.string("list"),
      dueAt = due, clearDue = clearDue, priority = priority, clearPriority = clearPrio,
      done = args.boolean("done"),
    )
    return if (ok) ToolResult.Success("Task #$id updated.") else notFound(id)
  }

  private suspend fun delete(args: Map<String, JsonElement>): ToolResult {
    val id = args.long("id") ?: return missingArg("id")
    return if (board.deleteTask(id)) ToolResult.Success("Task #$id deleted.") else notFound(id)
  }

  private fun lists(): ToolResult {
    val data = board.data.value
    return ToolResult.Success(
      data.lists.joinToString("\n") { l ->
        val open = data.tasks.count { it.listId == l.id && !it.done }
        val done = data.tasks.count { it.listId == l.id && it.done }
        "- ${l.name}: $open open, $done done" + if (l.id == INBOX_ID) " (default)" else ""
      },
    )
  }

  private fun notFound(id: Long) = ToolResult.Error("No task with id $id. Call list to see the ids.")
  private fun badDue(raw: String) = ToolResult.Error("Cannot read due '$raw'. Use YYYY-MM-DD or YYYY-MM-DD HH:mm.")
  private fun badPriority(raw: String) = ToolResult.Error("Unknown priority '$raw'. Use low, normal or high.")

  companion object {
    const val NAME = "task_list"

    /** "YYYY-MM-DD" means the start of that day; "YYYY-MM-DD HH:mm" a moment in local time. */
    internal fun parseDue(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
      val s = raw.trim().replace('T', ' ')
      return try {
        if (s.length <= 10) {
          LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
          LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant().toEpochMilli()
        }
      } catch (_: Exception) {
        null
      }
    }

    internal fun parsePriority(raw: String): TaskPriority? = when (raw.trim().lowercase()) {
      "low" -> TaskPriority.LOW
      "normal" -> TaskPriority.NORMAL
      "high" -> TaskPriority.HIGH
      else -> null
    }

    internal fun describe(t: TaskItem): String = buildString {
      append(t.title)
      if (t.done) append(" (done)")
      t.dueAt?.let { append(", due ").append(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) }
      t.priority?.let { append(", priority ").append(it.name.lowercase()) }
    }
  }
}
