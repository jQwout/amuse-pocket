package com.aiopenpocket.agent.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Inject
@SingleIn(AppScope::class)
class AlarmTool(
  private val context: Context,
) : Tool {

  override val riskClass = RiskClass.WRITE

  override val name = "alarm"
  override val description =
    "Будильник через системные часы: установить будильник на время или открыть список будильников."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonArray(listOf(JsonPrimitive("set_alarm"), JsonPrimitive("show_alarms"))),
            "description" to JsonPrimitive("Действие: set_alarm, show_alarms"),
          )),
          "hour" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Час будильника 0-23 (set_alarm)"),
          )),
          "minute" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Минута будильника 0-59 (set_alarm)"),
          )),
          "message" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Подпись будильника (set_alarm, опц.)"),
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
      "set_alarm" -> setAlarm(arguments)
      "show_alarms" -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Открыт список будильников.")
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun setAlarm(arguments: Map<String, JsonElement>): ToolResult {
    val hour = (arguments["hour"] as? JsonPrimitive)?.content?.toIntOrNull()
      ?: return ToolResult.Error("Missing 'hour'")
    val minute = (arguments["minute"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59) {
      return ToolResult.Error("Некорректное время: hour 0-23, minute 0-59.")
    }
    val message = (arguments["message"] as? JsonPrimitive)?.content

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
