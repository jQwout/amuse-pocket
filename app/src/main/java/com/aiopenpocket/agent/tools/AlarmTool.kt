package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.int
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class AlarmTool(
  private val context: Context,
  private val foreground: ForegroundState,
) : Tool {

  override val riskClass = RiskClass.WRITE

  override val name = "alarm"
  // It says explicitly when NOT to use this tool: it used to be the only thing the model could reach for
  // (heartbeat requires delay_minutes, and there was no current time in the prompt), and "remind me at
  // 19:00" consistently turned into a system alarm. The time is now put into the prompt — what remained
  // was to break the habit.
  override val description =
    "СИСТЕМНЫЙ БУДИЛЬНИК — со звонком, через приложение «Часы». Бери ТОЛЬКО когда человек " +
    "прямо просит будильник («поставь будильник», «разбуди меня»). Для «напомни», «напиши мне», " +
    "«не забудь» нужен heartbeat с mode=push, а не будильник: тихое уведомление вместо звонка. " +
    "Учти: отданный часам будильник обратно не отменить и не перечислить — можно лишь открыть Часы."
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
      "show_alarms" -> launch(
        intent = Intent(AlarmClock.ACTION_SHOW_ALARMS),
        what = "список будильников",
        opened = "Открыт список будильников.",
        notificationTitle = tr("Будильники", "Alarms"),
        notificationText = tr("Нажми, чтобы открыть «Часы».", "Tap to open the Clock app."),
      )
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
    return launch(
      intent = intent,
      what = "будильник на $time",
      opened = "Будильник на $time открыт в приложении часов.",
      notificationTitle = tr("Будильник на $time", "Alarm for $time"),
      notificationText = tr("Нажми, чтобы поставить его в «Часах».", "Tap to set it in the Clock app."),
    )
  }

  /**
   * On screen it opens Clock right away. In the background Android will not allow the launch (see
   * [launchExternalActivity]), so it shows a notification, and the model is told plainly that the
   * alarm is NOT set yet: the tool used to report success in this case while there was no alarm.
   *
   * Who reads what: [notificationTitle] and [notificationText] end up in setContentTitle/setContentText,
   * so a PERSON reads them and the callers pass them through [tr]. [what] and [opened], and every
   * ToolResult text below, are addressed to the MODEL and stay Russian on purpose (AGENTS.md) — they
   * are not shown anywhere.
   */
  private fun launch(
    intent: Intent,
    what: String,
    opened: String,
    notificationTitle: String,
    notificationText: String,
  ): ToolResult = when (val outcome = launchExternalActivity(context, foreground, intent, notificationTitle, notificationText)) {
    LaunchOutcome.Opened -> ToolResult.Success(opened)
    LaunchOutcome.Notified -> ToolResult.Success(
      "Приложение свёрнуто, а Android не даёт открыть «Часы» из фона. Действие ($what) ЕЩЁ НЕ " +
        "выполнено: пользователю отправлено уведомление, по тапу откроются «Часы». Так и скажи ему.",
    )
    LaunchOutcome.NotNotified -> ToolResult.Error(
      "Приложение свёрнуто, а Android не даёт открыть «Часы» из фона; уведомление тоже не показать — " +
        "нет разрешения. Действие ($what) НЕ выполнено: попроси пользователя открыть приложение и повторить.",
    )
    is LaunchOutcome.Failed -> ToolResult.Error("Не удалось открыть приложение часов: ${outcome.reason}")
  }
}
