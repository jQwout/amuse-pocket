package com.aiopenpocket.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
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

@Inject
@SingleIn(AppScope::class)
class EmailTool(
  private val context: Context,
) : Tool {

  override val name = "email"
  override val description =
    "Помогает составить письмо и открывает черновик в почтовом приложении пользователя. " +
    "Письмо НЕ отправляется автоматически — пользователь сам нажимает «Отправить»."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("compose"),
            "description" to JsonPrimitive("Действие: compose"),
          )),
          "to" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Email получателя (опц.)"),
          )),
          "subject" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Тема письма"),
          )),
          "body" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Текст письма"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'action'")
    if (action != "compose") return ToolResult.Error("Unknown action: $action")

    val to = (arguments["to"] as? JsonPrimitive)?.content
    val subject = (arguments["subject"] as? JsonPrimitive)?.content
    val body = (arguments["body"] as? JsonPrimitive)?.content

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
