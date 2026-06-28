package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.content.Context
import android.content.Intent
import android.net.Uri
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
class EmailTool(
  private val context: Context,
) : Tool {

  override val name = "email"
  override val description =
    "Помогает составить письмо и открывает черновик в почтовом приложении пользователя. " +
    "Письмо НЕ отправляется автоматически — пользователь сам нажимает «Отправить»."
  override val schema = toolSchema(name, description) {
    string("action", "Действие: compose", required = true, enum = listOf("compose"))
    string("to", "Email получателя (опц.)")
    string("subject", "Тема письма")
    string("body", "Текст письма")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    if (action != "compose") return ToolResult.Error("Unknown action: $action")

    val to = arguments.string("to")
    val subject = arguments.string("subject")
    val body = arguments.string("body")

    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
      if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
      if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
      if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
      context.startActivity(intent)
      ToolResult.Success("Черновик письма открыт в почтовом приложении. Отправьте его кнопкой «Отправить».")
    } catch (e: Exception) {
      ToolResult.Error("Не найдено почтовое приложение: ${e.message}")
    }
  }
}
