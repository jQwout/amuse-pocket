package com.aiopenpocket.ui.chat

// @trace heartbeat-napominalki-agenta-5f0442

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity

/** Читаемое расписание карточки reminder: "через N мин/ч N мин" (one_time) или "каждые N мин/ч" (periodic). */
fun formatSchedule(hb: HeartbeatEntity, now: Long = System.currentTimeMillis()): String {
  if (hb.kind == "periodic") {
    val m = hb.intervalMinutes ?: 0
    return if (m % 60 == 0L && m >= 60) "каждые ${m / 60} ч" else "каждые $m мин"
  }
  val left = ((hb.fireAt ?: now) - now).coerceAtLeast(0) / 60_000L
  return when {
    left >= 60 -> "через ${left / 60} ч ${left % 60} мин"
    else -> "через $left мин"
  }
}

/** Человекочитаемый статус heartbeat'а для карточки. */
fun formatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> "Сработало"
  "cancelled" -> "Отменено"
  "error" -> "Ошибка"
  else -> "Ждёт"
}
