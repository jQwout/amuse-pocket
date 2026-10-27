package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatCardFormatTest {

  private fun hb(
    kind: String = "one_time",
    fireAt: Long? = null,
    intervalMinutes: Long? = null,
    status: String = "scheduled",
  ) = HeartbeatEntity(
    id = "x", title = "T", mode = "push", conversationId = "c",
    kind = kind, fireAt = fireAt, intervalMinutes = intervalMinutes, status = status,
  )

  private val utc = java.util.TimeZone.getTimeZone("UTC")

  /** A fired or overdue one-time reminder shows the time, not "in 0 min". */
  @Test
  fun `due or finished one-time shows the fire time`() {
    val fireAt = 14 * 3_600_000L + 30 * 60_000L // 01.01.1970 14:30 UTC
    assertEquals("на 14:30", formatSchedule(hb(fireAt = fireAt, status = "fired"), fireAt + 60_000L, utc))
    assertEquals("на 14:30", formatSchedule(hb(fireAt = fireAt), fireAt + 5_000L, utc))
    assertEquals("на 14:30", formatSchedule(hb(fireAt = fireAt, status = "cancelled"), fireAt - 600_000L, utc))
    assertEquals("на 01.01 14:30", formatSchedule(hb(fireAt = fireAt, status = "fired"), fireAt + 86_400_000L, utc))
  }

  @Test
  fun `one-time future formats as relative minutes`() {
    val now = 1_000_000L
    assertEquals("через 30 мин", formatSchedule(hb(fireAt = now + 30 * 60_000L), now))
  }

  @Test
  fun `one-time under 2 hours formats as hours and minutes`() {
    val now = 0L
    assertEquals("через 1 ч 30 мин", formatSchedule(hb(fireAt = 90 * 60_000L), now))
  }

  /** The card is drawn milliseconds after scheduling — a fresh "in 2 minutes" must not become "in 1 min". */
  @Test
  fun `fresh reminder rounds up to the requested minutes`() {
    val now = 1_000_000L
    assertEquals("через 2 мин", formatSchedule(hb(fireAt = now + 2 * 60_000L - 50L), now))
    assertEquals("через 1 мин", formatSchedule(hb(fireAt = now + 1_000L), now))
  }

  @Test
  fun `whole hours drop the zero minutes`() {
    assertEquals("через 2 ч", formatSchedule(hb(fireAt = 120 * 60_000L), 0L))
  }

  @Test
  fun `periodic formats as every N`() {
    assertEquals("каждые 30 мин", formatSchedule(hb(kind = "periodic", intervalMinutes = 30), 0L))
    assertEquals("каждые 2 ч", formatSchedule(hb(kind = "periodic", intervalMinutes = 120), 0L))
  }

  @Test
  fun `statuses map to human labels`() {
    assertEquals("Ждёт", formatStatus(hb()))
    assertEquals("Сработало", formatStatus(hb(status = "fired")))
    assertEquals("Отменено", formatStatus(hb(status = "cancelled")))
    assertEquals("Ошибка", formatStatus(hb(status = "error")))
  }
}
