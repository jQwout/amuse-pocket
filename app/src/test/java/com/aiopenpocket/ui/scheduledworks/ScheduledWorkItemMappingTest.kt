package com.aiopenpocket.ui.scheduledworks

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.WorkInfo
import com.aiopenpocket.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ScheduledWorkItemMappingTest {

  private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

  private fun info(
    tags: Set<String>,
    state: WorkInfo.State = WorkInfo.State.ENQUEUED,
    nextScheduleTimeMillis: Long = Long.MAX_VALUE,
  ) = WorkInfo(
    uuid, state, tags, Data.EMPTY, Data.EMPTY, 0, 0, Constraints.NONE, 0L, null, nextScheduleTimeMillis,
  )

  @Test
  fun `work without work_manager tags is not shown`() {
    // A HeartbeatScheduler reminder: unique work without our tags, only the worker class's auto tag.
    assertNull(info(setOf("com.aiopenpocket.agent.workers.HeartbeatWorker")).toScheduledWorkItem())
  }

  @Test
  fun `work_manager tags map to title, message, unique name and type`() {
    val item = info(
      setOf("periodic", "title:Чек-ин", "msg:Как дела?", "id:abc", "com.example.NotificationWorker"),
      nextScheduleTimeMillis = 1_700_000_000_000L,
    ).toScheduledWorkItem()!!

    assertEquals(uuid.toString(), item.id)
    assertEquals("abc", item.uniqueName)
    assertEquals("Чек-ин", item.title)
    assertEquals("Как дела?", item.message)
    assertEquals("periodic", item.type)
    assertEquals(1_700_000_000_000L, item.nextScheduleTimeMillis)
  }

  @Test
  fun `missing id tag leaves no unique name so cancel falls back to the uuid`() {
    assertNull(info(setOf("one_time")).toScheduledWorkItem()!!.uniqueName)
  }

  @Test
  fun `running work has no next run time instead of a far-future date`() {
    val item = info(setOf("one_time", "id:x"), state = WorkInfo.State.RUNNING).toScheduledWorkItem()!!
    assertNull(item.nextScheduleTimeMillis)
    assertEquals("Выполняется", item.state.label())
  }

  @Test
  fun `state labels follow the English interface`() {
    try {
      AppLanguage.code = AppLanguage.EN
      for (state in WorkInfo.State.values()) {
        assertFalse(state.label(), Regex("[А-Яа-яЁё]").containsMatchIn(state.label()))
      }
      assertEquals("Running", WorkInfo.State.RUNNING.label())
    } finally {
      AppLanguage.code = AppLanguage.RU
    }
  }
}
