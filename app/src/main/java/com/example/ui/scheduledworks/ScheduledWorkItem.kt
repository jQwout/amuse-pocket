package com.aiopenpocket.ui.scheduledworks

data class ScheduledWorkItem(
  val id: String,
  val title: String,
  val message: String,
  val state: String,
  val type: String,
  val nextScheduleTimeMillis: Long,
)
