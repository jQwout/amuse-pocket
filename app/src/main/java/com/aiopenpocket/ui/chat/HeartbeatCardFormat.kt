package com.aiopenpocket.ui.chat

// @trace heartbeat-napominalki-agenta-5f0442

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A readable schedule for the reminder card: "in N min/h N min" (one_time) or "every N min/h" (periodic).
 * A fired, cancelled or already due one-time reminder shows the fire time ("at 14:30"), not "in 0 min".
 */
fun formatSchedule(
  hb: HeartbeatEntity,
  now: Long = System.currentTimeMillis(),
  zone: TimeZone = TimeZone.getDefault(),
): String {
  if (hb.kind == "periodic") {
    val m = hb.intervalMinutes ?: 0
    return if (m % 60 == 0L && m >= 60) tr("каждые ${m / 60} ч", "every ${m / 60} h") else tr("каждые $m мин", "every $m min")
  }
  val fireAt = hb.fireAt
  if (fireAt != null && (fireAt <= now || hb.status != "scheduled")) return formatFireTime(fireAt, now, zone)
  // Rounding UP: the card is drawn milliseconds after scheduling, and floor turned a fresh "in 2 minutes"
  // into "in 1 min". Ceil does not lie the other way either — the reminder is never before its time.
  val leftMs = ((fireAt ?: now) - now).coerceAtLeast(0)
  val left = (leftMs + 59_999L) / 60_000L
  return when {
    left >= 60 && left % 60 == 0L -> tr("через ${left / 60} ч", "in ${left / 60} h")
    left >= 60 -> tr("через ${left / 60} ч ${left % 60} мин", "in ${left / 60} h ${left % 60} min")
    else -> tr("через $left мин", "in $left min")
  }
}

/** "at 14:30" today, "at 24.10 14:30" on another day. SimpleDateFormat, not java.time: minSdk 24 without desugaring. */
private fun formatFireTime(fireAt: Long, now: Long, zone: TimeZone): String {
  fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }
  val day = fmt("yyyyMMdd")
  val pattern = if (day.format(Date(fireAt)) == day.format(Date(now))) "HH:mm" else "dd.MM HH:mm"
  return tr("на ${fmt(pattern).format(Date(fireAt))}", "at ${fmt(pattern).format(Date(fireAt))}")
}

/** A human-readable heartbeat status for the card. */
fun formatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> tr("Сработало", "Done")
  "cancelled" -> tr("Отменено", "Cancelled")
  "error" -> tr("Ошибка", "Error")
  else -> tr("Ждёт", "Waiting")
}
