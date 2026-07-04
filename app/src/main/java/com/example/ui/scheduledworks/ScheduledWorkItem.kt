package com.aiopenpocket.ui.scheduledworks

// @trace background-tasks-15a34a

data class ScheduledWorkItem(
  val id: String,
  val title: String,
  val message: String,
  val state: String,
  val type: String,
  val nextScheduleTimeMillis: Long,
)
