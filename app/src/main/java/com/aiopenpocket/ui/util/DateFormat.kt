package com.aiopenpocket.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.aiopenpocket.i18n.tr

// ThreadLocal: SimpleDateFormat не потокобезопасен, а formatDateTime — публичная util (может
// вызываться не только с Main). Общий mutable-инстанс при конкурентном format() давал искажённые
// даты/AIOOBE; ThreadLocal даёт по инстансу на поток без аллокации на каждый вызов.
private val DATE_TIME = ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

fun formatDateTime(timestamp: Long): String = DATE_TIME.get()!!.format(Date(timestamp))

/** Сегодня/Вчера с временем, иначе «N дней назад» или дата. */
fun formatRelativeDate(timestamp: Long): String {
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = timestamp }
  val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

  fun sameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
      a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

  val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

  return when {
    sameDay(now, then) -> tr("Сегодня, $time", "Today, $time")
    sameDay(yesterday, then) -> tr("Вчера, $time", "Yesterday, $time")
    else -> {
      val days = ((now.timeInMillis - timestamp) / (1000L * 60 * 60 * 24)).toInt()
      if (days in 2..6) {
        tr("$days ${pluralDays(days)} назад", "$days days ago")
      } else {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
      }
    }
  }
}

private fun pluralDays(days: Int): String {
  val mod10 = days % 10
  val mod100 = days % 100
  return when {
    mod10 == 1 && mod100 != 11 -> "день"
    mod10 in 2..4 && mod100 !in 12..14 -> "дня"
    else -> "дней"
  }
}
