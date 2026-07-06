package com.aiopenpocket.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_TIME = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

fun formatDateTime(timestamp: Long): String = DATE_TIME.format(Date(timestamp))
