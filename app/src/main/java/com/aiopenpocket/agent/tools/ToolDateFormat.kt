package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Короткий формат даты/времени для текстовых ответов тулов (календарь и т.п.). */
internal fun formatToolDateTime(millis: Long): String =
  SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
