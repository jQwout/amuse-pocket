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
