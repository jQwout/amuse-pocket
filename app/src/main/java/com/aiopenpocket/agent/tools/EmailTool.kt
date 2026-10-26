package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class EmailTool(
  private val context: Context,
  private val foreground: ForegroundState,
) : Tool {

  // Composer only — opens a draft the user sends manually — but it leaves the trust boundary.
  override val riskClass = RiskClass.EXTERNAL

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
    }

    // In the background Android will not let us open the mail app (see launchExternalActivity) — then the
    // draft waits for a tap on the notification, and the model is told so plainly: the tool used to
    // report "opened" while nothing opened.
    val notificationText = subject?.takeIf { it.isNotBlank() }?.let { "«$it» — нажми, чтобы открыть в почте." }
      ?: "Нажми, чтобы открыть его в почте."
    return when (val outcome = launchExternalActivity(context, foreground, intent, "Черновик письма готов", notificationText)) {
      LaunchOutcome.Opened ->
        ToolResult.Success("Черновик письма открыт в почтовом приложении. Отправьте его кнопкой «Отправить».")
      LaunchOutcome.Notified -> ToolResult.Success(
        "Приложение свёрнуто, а Android не даёт открыть почту из фона. Черновик ЕЩЁ НЕ открыт: " +
          "пользователю отправлено уведомление, по тапу откроется почта с этим письмом. Так и скажи ему.",
      )
      LaunchOutcome.NotNotified -> ToolResult.Error(
        "Приложение свёрнуто, а Android не даёт открыть почту из фона; уведомление тоже не показать — " +
          "нет разрешения. Черновик НЕ открыт: попроси пользователя открыть приложение и повторить.",
      )
      is LaunchOutcome.Failed -> ToolResult.Error("Не найдено почтовое приложение: ${outcome.reason}")
    }
  }
}
