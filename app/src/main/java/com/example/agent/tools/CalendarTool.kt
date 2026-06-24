package com.aiopenpocket.agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Inject
@SingleIn(AppScope::class)
class CalendarTool(
  private val context: Context,
) : Tool {

  override val name = "calendar"
  override val description =
    "Работа с календарём устройства: список ближайших событий, создание и удаление событий. " +
    "Требует разрешения календаря (выдаётся в Настройках)."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonArray(listOf(JsonPrimitive("list_events"), JsonPrimitive("create_event"), JsonPrimitive("delete_event"))),
            "description" to JsonPrimitive("Действие: list_events, create_event, delete_event"),
          )),
          "days_ahead" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("На сколько дней вперёд смотреть события (list_events, по умолчанию 7)"),
          )),
          "title" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Название события (create_event)"),
          )),
          "start" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Начало события — Unix-время в миллисекундах (create_event)"),
          )),
          "end" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Конец события — Unix-время в миллисекундах (create_event, опц.)"),
          )),
          "duration_minutes" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Длительность в минутах, если не указан end (create_event, по умолчанию 60)"),
          )),
          "description" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Описание события (create_event, опц.)"),
          )),
          "location" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Место события (create_event, опц.)"),
          )),
          "event_id" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("ID события для удаления (delete_event)"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'action'")

    return when (action) {
      "list_events" -> listEvents(arguments)
      "create_event" -> createEvent(arguments)
      "delete_event" -> deleteEvent(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun listEvents(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.READ_CALENDAR, "чтение календаря")?.let { return it }

    val daysAhead = (arguments["days_ahead"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 7L
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
        events.append("• [$id] ${formatDateTime(begin)} — $title")
        if (!location.isNullOrBlank()) events.append(" @ $location")
        events.append('\n')
        count++
      }
    }

    return if (count == 0) {
      ToolResult.Success("На ближайшие $daysAhead дн. событий не найдено.")
    } else {
      ToolResult.Success("События ($count):\n${events.trimEnd()}")
    }
  }

  private fun createEvent(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.WRITE_CALENDAR, "запись в календарь")?.let { return it }

    val title = (arguments["title"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'title'")
    val start = (arguments["start"] as? JsonPrimitive)?.content?.toLongOrNull()
      ?: return ToolResult.Error("Missing 'start' (Unix-время в мс)")
    val durationMin = (arguments["duration_minutes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 60L
    val end = (arguments["end"] as? JsonPrimitive)?.content?.toLongOrNull()
      ?: (start + durationMin * 60L * 1000L)
    val description = (arguments["description"] as? JsonPrimitive)?.content
    val location = (arguments["location"] as? JsonPrimitive)?.content

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
    val id = uri.lastPathSegment
    return ToolResult.Success("Событие создано (id=$id): «$title» на ${formatDateTime(start)}")
  }

  private fun deleteEvent(arguments: Map<String, JsonElement>): ToolResult {
    context.requirePermission(Manifest.permission.WRITE_CALENDAR, "запись в календарь")?.let { return it }

    val eventId = (arguments["event_id"] as? JsonPrimitive)?.content?.toLongOrNull()
      ?: return ToolResult.Error("Missing 'event_id'")
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    val deleted = context.contentResolver.delete(uri, null, null)
    return if (deleted > 0) {
      ToolResult.Success("Событие $eventId удалено.")
    } else {
      ToolResult.Error("Событие $eventId не найдено.")
    }
  }

  /** Возвращает ID первого доступного для записи календаря. */
  private fun primaryCalendarId(): Long? {
    val projection = arrayOf(
      CalendarContract.Calendars._ID,
      CalendarContract.Calendars.IS_PRIMARY,
    )
    context.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      projection,
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
      null,
      "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { cursor ->
      if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
  }

  private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
}
