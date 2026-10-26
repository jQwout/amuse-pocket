package com.aiopenpocket.agent.core.prompt

// @trace heartbeat-napominalki-agenta-5f0442
// @trace structured-plan-engine-907066

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The "Now" section for system prompts — one for all of them: the chat ([PrePromptAssembler]), the
 * planner ([com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder]) and the plan step executor
 * ([com.aiopenpocket.agent.core.plan.execution.PlanExecutor]).
 *
 * Without the current moment the model cannot turn "at 19:00" or "on Friday" into a delay or a date: in
 * the chat that made every "remind me" go to the alarm (it takes absolute time, heartbeat takes relative
 * time), and the planner and plan steps scheduled at random. The format is shared, otherwise the chat
 * and the plan would understand "now" differently.
 */
object CurrentMoment {

  /**
   * «Пятница, 25 июля 2026, 14:37 (Europe/Moscow, UTC+3).» (Friday, July 25, 2026, 14:37 …)
   *
   * The weekday and the year are not for looks: without the weekday the model cannot work out "on
   * Friday", without the year and month — "in 3 days" across a month boundary, without the time zone —
   * any absolute arrangement. The locale is hard-coded Russian, like the rest of the prompt.
   */
  fun format(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val now = Date(nowMillis)
    val stamp = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru"))
      .apply { timeZone = zone }
      .format(now)
    val offsetMinutes = zone.getOffset(now.time) / 60_000
    val sign = if (offsetMinutes < 0) "-" else "+"
    val absMinutes = kotlin.math.abs(offsetMinutes)
    val offset = if (absMinutes % 60 == 0) {
      "$sign${absMinutes / 60}"
    } else {
      "$sign${absMinutes / 60}:${(absMinutes % 60).toString().padStart(2, '0')}"
    }
    return "${stamp.replaceFirstChar { it.uppercase() }} (${zone.id}, UTC$offset)."
  }

  /**
   * The ready section with a heading: the moment + the rule "count from it yourself". The trusted zone —
   * the app puts it in, not the user, so callers place it ABOVE any envelopes of untrusted data.
   */
  fun section(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String = buildString {
    appendLine("## Сейчас")
    appendLine(format(nowMillis, zone))
    append("Считай «через сколько» от этого момента — не угадывай и не спрашивай у пользователя, который час.")
  }
}
