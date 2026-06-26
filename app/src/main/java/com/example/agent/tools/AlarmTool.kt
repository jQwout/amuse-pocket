package com.aiopenpocket.agent.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.int
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class AlarmTool(
  private val context: Context,
) : Tool {

  override val name = "alarm"
  override val description =
    "Будильник через системные часы: установить будильник на время или открыть список будильников."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: set_alarm, show_alarms",
      required = true,
      enum = listOf("set_alarm", "show_alarms"),
    )
    integer("hour", "Час будильника 0-23 (set_alarm)")
    integer("minute", "Минута будильника 0-59 (set_alarm)")
    string("message", "Подпись будильника (set_alarm, опц.)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    return when (action) {
      "set_alarm" -> setAlarm(arguments)
      "show_alarms" -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Открыт список будильников.")
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun setAlarm(arguments: Map<String, JsonElement>): ToolResult {
    val hour = arguments.int("hour") ?: return missingArg("hour")
    val minute = arguments.int("minute") ?: 0
    if (hour !in 0..23 || minute !in 0..59) {
      return ToolResult.Error("Некорректное время: hour 0-23, minute 0-59.")
    }
    val message = arguments.string("message")

    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
      putExtra(AlarmClock.EXTRA_HOUR, hour)
      putExtra(AlarmClock.EXTRA_MINUTES, minute)
      if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
    }
    val time = "%02d:%02d".format(hour, minute)
    return launch(intent, "Будильник на $time открыт в приложении часов.")
  }

  private fun launch(intent: Intent, successMessage: String): ToolResult {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      ToolResult.Success(successMessage)
    } catch (e: Exception) {
      ToolResult.Error("Не удалось открыть приложение часов: ${e.message}")
    }
  }
}
