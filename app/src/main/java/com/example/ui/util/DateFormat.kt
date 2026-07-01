package com.aiopenpocket.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DATE_TIME = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

fun formatDateTime(timestamp: Long): String = DATE_TIME.format(Date(timestamp))

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
    sameDay(now, then) -> "Сегодня, $time"
    sameDay(yesterday, then) -> "Вчера, $time"
    else -> {
      val days = ((now.timeInMillis - timestamp) / (1000L * 60 * 60 * 24)).toInt()
      if (days in 2..6) {
        "$days ${pluralDays(days)} назад"
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
