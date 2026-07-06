package com.aiopenpocket.agent.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class NotificationsTool(
  private val context: Context,
) : Tool {

  override val riskClass = RiskClass.WRITE

  override val name = "notifications"
  override val description = "Показывает локальное push-уведомление пользователю."
  override val schema = toolSchema(name, description) {
    string("title", "Заголовок уведомления", required = true)
    string("message", "Текст уведомления", required = true)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val title = arguments.string("title") ?: return missingArg("title")
    val message = arguments.string("message") ?: return missingArg("message")

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "AI Agent Notifications", NotificationManager.IMPORTANCE_DEFAULT),
      )
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
