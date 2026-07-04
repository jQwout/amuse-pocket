package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.notification.showAgentNotification
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class NotificationsTool(
  private val context: Context,
) : Tool {

  override val name = "notifications"
  override val description = "Показывает локальное push-уведомление пользователю."
  override val schema = toolSchema(name, description) {
    string("title", "Заголовок уведомления", required = true)
    string("message", "Текст уведомления", required = true)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val title = arguments.string("title") ?: return missingArg("title")
    val message = arguments.string("message") ?: return missingArg("message")

    val intent = Intent(context, com.aiopenpocket.MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val id = System.currentTimeMillis().toInt()
    val shown = showAgentNotification(context, title, message, id, pendingIntent)
    if (!shown) {
      return ToolResult.Error(
        "Нет разрешения на уведомления. Выдайте его в Настройках → Интеграции.",
        recoverable = false,
      )
    }
    return ToolResult.Success("Notification shown: id=$id")
  }
}
