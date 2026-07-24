package com.aiopenpocket.agent.tools

import java.util.Calendar

/** Local start-of-day (00:00:00.000) in millis. Shared by tools that do calendar-day math. */
internal fun startOfTodayMillis(): Long =
  Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
  }.timeInMillis
