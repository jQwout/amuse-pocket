package com.aiopenpocket.agent.tools

import android.Manifest
import android.content.Context
import android.provider.Telephony
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

@Inject
@SingleIn(AppScope::class)
class SmsTool(
  private val context: Context,
) : Tool {

  override val name = "sms"
  override val description =
    "Чтение последних входящих SMS пользователя. Только чтение, без отправки. " +
    "Требует разрешения на чтение SMS (выдаётся в Настройках)."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("read_recent"),
            "description" to JsonPrimitive("Действие: read_recent"),
          )),
          "limit" to JsonObject(mapOf(
            "type" to JsonPrimitive("integer"),
            "description" to JsonPrimitive("Сколько последних сообщений вернуть (по умолчанию 10)"),
          )),
          "from" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Фильтр по отправителю/номеру (опц.)"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'action'")
    if (action != "read_recent") return ToolResult.Error("Unknown action: $action")

    context.requirePermission(Manifest.permission.READ_SMS, "чтение SMS")?.let { return it }

    val limit = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
    val from = (arguments["from"] as? JsonPrimitive)?.content

    val projection = arrayOf(
      Telephony.Sms.ADDRESS,
      Telephony.Sms.BODY,
      Telephony.Sms.DATE,
    )
    val selection = if (!from.isNullOrBlank()) "${Telephony.Sms.ADDRESS} LIKE ?" else null
    val selectionArgs = if (!from.isNullOrBlank()) arrayOf("%$from%") else null

    val out = StringBuilder()
    var count = 0
    context.contentResolver.query(
      Telephony.Sms.Inbox.CONTENT_URI,
      projection,
      selection,
      selectionArgs,
      "${Telephony.Sms.DATE} DESC",
    )?.use { cursor ->
      while (cursor.moveToNext() && count < limit) {
        val address = cursor.getString(0) ?: "(неизвестно)"
        val body = cursor.getString(1) ?: ""
        val date = cursor.getLong(2)
        out.append("• ${formatDateTime(date)} от $address: $body\n")
        count++
      }
    }

    return if (count == 0) {
      ToolResult.Success("Сообщений не найдено.")
    } else {
      ToolResult.Success("Последние SMS ($count):\n${out.trimEnd()}")
    }
  }

  private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
}
