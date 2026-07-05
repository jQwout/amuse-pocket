package com.aiopenpocket.agent.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
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
class NotificationsTool(
  private val context: Context,
) : Tool {

  override val riskClass = RiskClass.WRITE

  override val name = "notifications"
  override val description = "Показывает локальное push-уведомление пользователю."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "title" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Заголовок уведомления"),
          )),
          "message" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Текст уведомления"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("message"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val title = (arguments["title"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'title'")
    val message = (arguments["message"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'message'")

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "AI Agent Notifications",
        NotificationManager.IMPORTANCE_DEFAULT,
      )
      notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(context, com.aiopenpocket.MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(message)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .build()

    val id = System.currentTimeMillis().toInt()
    notificationManager.notify(id, notification)

    return ToolResult.Success("Notification shown: id=$id")
  }

  companion object {
    const val CHANNEL_ID = "ai_agent_channel"
  }
}
