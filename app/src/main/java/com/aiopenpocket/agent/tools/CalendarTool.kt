package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.util.TimeZone

@Inject
@SingleIn(AppScope::class)
class CalendarTool(
  private val context: Context,
) : Tool {

  // delete_event is irreversible; event titles come from other people. Highest action wins.
  override val riskClass = RiskClass.DESTRUCTIVE
  override val returnsUntrustedContent = true

  // Per-call risk (Task 9): list_events only reads the device calendar; create_event writes a new
  // event; delete_event is the irreversible one. An unrecognized/missing action fails closed to the
  // tool-wide DESTRUCTIVE ceiling rather than READ - execute() below still rejects it either way.
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "list_events" -> RiskClass.READ
    "create_event" -> RiskClass.WRITE
    "delete_event" -> RiskClass.DESTRUCTIVE
    else -> riskClass
  }

  override val name = "calendar"
  override val description =
    "Работа с календарём устройства: список ближайших событий, создание и удаление событий. " +
    "Требует разрешения календаря (выдаётся в Настройках)."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: list_events, create_event, delete_event",
      required = true,
      enum = listOf("list_events", "create_event", "delete_event"),
    )
    integer("days_ahead", "На сколько дней вперёд смотреть события (list_events, по умолчанию 7)")
    string("title", "Название события (create_event)")
    integer("start", "Начало события — Unix-время в миллисекундах (create_event)")
    integer("end", "Конец события — Unix-время в миллисекундах (create_event, опц.)")
    integer("duration_minutes", "Длительность в минутах, если не указан end (create_event, по умолчанию 60)")
    string("description", "Описание события (create_event, опц.)")
    string("location", "Место события (create_event, опц.)")
    integer("event_id", "ID события для удаления (delete_event)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    return when (action) {
      "list_events" -> listEvents(arguments)
      "create_event" -> createEvent(arguments)
      "delete_event" -> deleteEvent(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun listEvents(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.READ_CALENDAR, "чтение календаря")?.let { return it }

    val daysAhead = arguments.long("days_ahead") ?: 7L
    val now = System.currentTimeMillis()
    val until = now + daysAhead * 24L * 60L * 60L * 1000L

    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, now)
    ContentUris.appendId(builder, until)

    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.END,
      CalendarContract.Instances.EVENT_LOCATION,
    )

    val events = StringBuilder()
    var count = 0
    context.contentResolver.query(
      builder.build(),
      projection,
      null,
      null,
      "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.getLong(0)
        val title = cursor.getString(1) ?: "(без названия)"
        val begin = cursor.getLong(2)
        val location = cursor.getString(4)
        events.append("• [$id] ${formatToolDateTime(begin)} — $title")
        if (!location.isNullOrBlank()) events.append(" @ $location")
        events.append('\n')
        count++
      }
    }

    return if (count == 0) ToolResult.Success("На ближайшие $daysAhead дн. событий не найдено.")
    else ToolResult.Success("События ($count):\n${events.trimEnd()}")
  }

  private fun createEvent(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.WRITE_CALENDAR, "запись в календарь")?.let { return it }

    val title = arguments.string("title") ?: return missingArg("title")
    val start = arguments.long("start")
      ?: return ToolResult.Error("Missing 'start' (Unix-время в мс)")
    val durationMin = arguments.long("duration_minutes") ?: 60L
    val end = arguments.long("end") ?: (start + durationMin * 60L * 1000L)
    val description = arguments.string("description")
    val location = arguments.string("location")

    val calendarId = primaryCalendarId()
      ?: return ToolResult.Error("Не найден доступный календарь на устройстве.")

    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DTSTART, start)
      put(CalendarContract.Events.DTEND, end)
      put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
      if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
      if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
    }

    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
      ?: return ToolResult.Error("Не удалось создать событие.")
    return ToolResult.Success("Событие создано (id=${uri.lastPathSegment}): «$title» на ${formatToolDateTime(start)}")
  }

  private fun deleteEvent(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.WRITE_CALENDAR, "запись в календарь")?.let { return it }

    val eventId = arguments.long("event_id") ?: return missingArg("event_id")
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    val deleted = context.contentResolver.delete(uri, null, null)
    return if (deleted > 0) ToolResult.Success("Событие $eventId удалено.")
    else ToolResult.Error("Событие $eventId не найдено.")
  }

  /** Returns the ID of the first calendar available for writing. */
  private fun primaryCalendarId(): Long? {
    context.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
      null,
      "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { cursor ->
      if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
  }
}
