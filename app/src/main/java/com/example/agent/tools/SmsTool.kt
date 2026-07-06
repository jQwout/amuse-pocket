package com.aiopenpocket.agent.tools

import android.Manifest
import android.content.Context
import android.provider.Telephony
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Inject
@SingleIn(AppScope::class)
class SmsTool(
  private val context: Context,
) : Tool {

  // Incoming SMS bodies are attacker-controllable text: mark untrusted so the loop wraps them.
  override val returnsUntrustedContent = true

  override val name = "sms"
  override val description =
    "Чтение последних входящих SMS пользователя. Только чтение, без отправки. " +
    "Требует разрешения на чтение SMS (выдаётся в Настройках)."
  override val schema = toolSchema(name, description) {
    string("action", "Действие: read_recent", required = true, enum = listOf("read_recent"))
    integer("limit", "Сколько последних сообщений вернуть (по умолчанию 10)")
    string("from", "Фильтр по отправителю/номеру (опц.)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    if (action != "read_recent") return ToolResult.Error("Unknown action: $action")

    context.requirePermission(Manifest.permission.READ_SMS, "чтение SMS")?.let { return it }

    val limit = arguments.int("limit") ?: 10
    val from = arguments.string("from")

    val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
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
        out.append("• ${formatSmsDate(date)} от $address: $body\n")
        count++
      }
    }

    return if (count == 0) ToolResult.Success("Сообщений не найдено.")
    else ToolResult.Success("Последние SMS ($count):\n${out.trimEnd()}")
  }

  private fun formatSmsDate(millis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
}
