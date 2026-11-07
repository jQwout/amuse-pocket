package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace a-timer-in-the-phone-s-clock-not-in-the-chat-cfafda

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

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
    "ТАЙМЕР («поставь таймер на 10 минут», «засеки 3 минуты») — set_timer: он сразу идёт в «Часах» " +
    "телефона, его видно в шторке и там же можно остановить; не heartbeat и не карточка render_card. " +
    "Учти: отданный часам будильник или таймер обратно не отменить и не перечислить — можно лишь " +
    "открыть Часы (show_alarms, show_timers)."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: set_alarm, show_alarms, set_timer, show_timers",
      required = true,
      enum = listOf("set_alarm", "show_alarms", "set_timer", "show_timers"),
    )
    integer("hour", "Час будильника 0-23 (set_alarm)")
    integer("minute", "Минута будильника 0-59 (set_alarm)")
    integer("minutes", "Длина таймера, минуты (set_timer; вместе с seconds)")
    integer("seconds", "Длина таймера, секунды (set_timer; вместе с minutes)")
    string("message", "Подпись будильника или таймера (set_alarm, set_timer, опц.)")
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
      "set_timer" -> setTimer(arguments)
      "show_timers" -> launch(
        intent = Intent(AlarmClock.ACTION_SHOW_TIMERS),
        what = "список таймеров",
        opened = "Открыт список таймеров.",
        notificationTitle = tr("Таймеры", "Timers"),
        notificationText = tr("Нажми, чтобы открыть «Часы».", "Tap to open the Clock app."),
      )
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  /**
   * The owner, Nov 7, 2026, after the Claude app: "set a timer" must be the phone's own timer - running in
   * Clock, in the status bar, stoppable there - not the chat's countdown that was lost with the card or an
   * internal reminder nobody could find again. Set without opening Clock (skip UI) and without counting as
   * the person leaving the app, so a call on screen does not shrink into its mini window. On screen the
   * result is the chat's timer card itself (see [isTimerCard]).
   */
  private fun setTimer(arguments: Map<String, JsonElement>): ToolResult {
    val length = (arguments.int("minutes") ?: 0) * 60 + (arguments.int("seconds") ?: 0)
    if (length !in 1..MAX_TIMER_SEC) {
      return ToolResult.Error("Некорректная длина таймера: от 1 секунды до 24 часов (minutes, seconds).")
    }
    val message = arguments.string("message")?.trim()?.takeIf { it.isNotEmpty() }
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
      putExtra(AlarmClock.EXTRA_LENGTH, length)
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      if (message != null) putExtra(AlarmClock.EXTRA_MESSAGE, message)
      addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
    val span = formatLength(length)
    return when (val outcome = launchExternalActivity(
      context,
      foreground,
      intent,
      tr("Таймер на $span", "Timer for $span"),
      tr("Нажми, чтобы запустить его в «Часах».", "Tap to start it in the Clock app."),
    )) {
      LaunchOutcome.Opened -> ToolResult.Success(timerCard(message ?: tr("Таймер", "Timer"), length))
      else -> report(outcome, what = "таймер на $span", opened = "")
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
  ): ToolResult = report(launchExternalActivity(context, foreground, intent, notificationTitle, notificationText), what, opened)

  private fun report(outcome: LaunchOutcome, what: String, opened: String): ToolResult = when (outcome) {
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

  companion object {
    /** Clock's own ceiling for a timer: 24 hours. */
    private const val MAX_TIMER_SEC = 24 * 60 * 60

    /**
     * The chat's timer card for a timer already running in Clock: what it is for, how long, and a button
     * to Clock's timers. The model reads the same JSON as "the timer is set".
     */
    internal fun timerCard(label: String, durationSec: Int): String = buildJsonObject {
      put("template", "timer")
      putJsonObject("params") {
        put("label", label)
        put("duration_sec", durationSec)
        put("set_on_device", true)
      }
    }.toString()

    /** Whether an `alarm` result is [timerCard] - a card for the chat, not text for the model only. */
    fun isTimerCard(data: String): Boolean = data.startsWith("""{"template":"timer"""")

    /** 3:00, 1:30, 1:00:00 - the way Clock writes a length. */
    fun formatLength(seconds: Int): String {
      val h = seconds / 3600
      val m = seconds % 3600 / 60
      val s = seconds % 60
      return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
  }
}
