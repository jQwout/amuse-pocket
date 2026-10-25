package com.aiopenpocket.agent.core.prompt

// @trace heartbeat-napominalki-agenta-5f0442
// @trace structured-plan-engine-907066

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Секция «Сейчас» для системных промптов — одна на все: чат ([PrePromptAssembler]), планировщик
 * ([com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder]) и исполнитель шага плана
 * ([com.aiopenpocket.agent.core.plan.execution.PlanExecutor]).
 *
 * Без текущего момента модель не может перевести «в 19:00» или «в пятницу» в задержку или дату: в
 * чате из-за этого любое «напомни» уходило в будильник (у него абсолютное время, у heartbeat —
 * относительное), а планировщик и шаг плана ставили расписание наугад. Формат — общий, иначе чат и
 * план понимали бы «сейчас» по-разному.
 */
object CurrentMoment {

  /**
   * «Пятница, 25 июля 2026, 14:37 (Europe/Moscow, UTC+3).»
   *
   * День недели и год здесь не для красоты: без дня недели модель не разберёт «в пятницу», без года
   * и месяца — «через 3 дня» на стыке месяцев, без пояса — любую абсолютную договорённость. Локаль
   * жёстко русская, как и весь остальной промпт.
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
   * Готовая секция с заголовком: момент + правило «считай от него сам». Доверенная зона — её
   * подставляет приложение, а не пользователь, поэтому вызывающие ставят её ВЫШЕ любых конвертов
   * недоверенных данных.
   */
  fun section(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String = buildString {
    appendLine("## Сейчас")
    appendLine(format(nowMillis, zone))
    append("Считай «через сколько» от этого момента — не угадывай и не спрашивай у пользователя, который час.")
  }
}
