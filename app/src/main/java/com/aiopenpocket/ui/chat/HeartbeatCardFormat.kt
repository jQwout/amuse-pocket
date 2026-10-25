package com.aiopenpocket.ui.chat

// @trace heartbeat-napominalki-agenta-5f0442

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Читаемое расписание карточки reminder: "через N мин/ч N мин" (one_time) или "каждые N мин/ч" (periodic).
 * Сработавшее, отменённое или уже наступившее разовое — время срабатывания ("на 14:30"), а не "через 0 мин".
 */
fun formatSchedule(
  hb: HeartbeatEntity,
  now: Long = System.currentTimeMillis(),
  zone: TimeZone = TimeZone.getDefault(),
): String {
  if (hb.kind == "periodic") {
    val m = hb.intervalMinutes ?: 0
    return if (m % 60 == 0L && m >= 60) "каждые ${m / 60} ч" else "каждые $m мин"
  }
  val fireAt = hb.fireAt
  if (fireAt != null && (fireAt <= now || hb.status != "scheduled")) return formatFireTime(fireAt, now, zone)
  // Округление ВВЕРХ: карточка рисуется через миллисекунды после постановки, и floor превращал свежее
  // «через 2 минуты» в «через 1 мин». Ceil не врёт и в другую сторону — напоминание не раньше срока.
  val leftMs = ((fireAt ?: now) - now).coerceAtLeast(0)
  val left = (leftMs + 59_999L) / 60_000L
  return when {
    left >= 60 && left % 60 == 0L -> "через ${left / 60} ч"
    left >= 60 -> "через ${left / 60} ч ${left % 60} мин"
    else -> "через $left мин"
  }
}

/** "на 14:30" сегодня, "на 24.10 14:30" в другой день. SimpleDateFormat, а не java.time: minSdk 24 без desugaring. */
private fun formatFireTime(fireAt: Long, now: Long, zone: TimeZone): String {
  fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }
  val day = fmt("yyyyMMdd")
  val pattern = if (day.format(Date(fireAt)) == day.format(Date(now))) "HH:mm" else "dd.MM HH:mm"
  return "на ${fmt(pattern).format(Date(fireAt))}"
}

/** Человекочитаемый статус heartbeat'а для карточки. */
fun formatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> "Сработало"
  "cancelled" -> "Отменено"
  "error" -> "Ошибка"
  else -> "Ждёт"
}
