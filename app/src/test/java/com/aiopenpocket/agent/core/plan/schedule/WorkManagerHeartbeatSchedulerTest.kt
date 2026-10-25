package com.aiopenpocket.agent.core.plan.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.tools.HeartbeatTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Задержка WorkManager считается в миллисекундах от [HeartbeatEntity.fireAt]: раньше минуты
 * округлялись вниз, и напоминание «через 2 минуты» ставилось на одну минуту (срабатывало раньше срока).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerHeartbeatSchedulerTest {

  private lateinit var context: Context
  private lateinit var scheduler: WorkManagerHeartbeatScheduler

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    scheduler = WorkManagerHeartbeatScheduler(context)
  }

  private fun initialDelayOf(id: String): Long =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(HeartbeatTool.workName(id)).get().single().initialDelayMillis

  @Test
  fun `one-time delay is not floored to whole minutes`() = runTest {
    val fireAt = System.currentTimeMillis() + 2 * 60_000L
    scheduler.schedule(HeartbeatEntity(id = "a", title = "T", mode = "push", conversationId = "c", kind = "one_time", fireAt = fireAt))

    val delay = initialDelayOf("a")
    // Старый код давал ровно 60 000 (2 мин − пара мс → floor = 1 мин).
    assertTrue("delay=$delay", delay in 110_000L..120_000L)
  }

  @Test
  fun `periodic first run keeps millisecond precision`() = runTest {
    val fireAt = System.currentTimeMillis() + 20 * 60_000L
    scheduler.schedule(
      HeartbeatEntity(
        id = "p", title = "T", mode = "agent", conversationId = "c", kind = "periodic",
        fireAt = fireAt, intervalMinutes = 30,
      ),
    )

    val delay = initialDelayOf("p")
    assertTrue("delay=$delay", delay in (20 * 60_000L - 10_000L)..(20 * 60_000L))
  }

  @Test
  fun `past fire time arms with zero delay`() = runTest {
    scheduler.schedule(
      HeartbeatEntity(id = "z", title = "T", mode = "push", conversationId = "c", kind = "one_time", fireAt = 1L),
    )

    assertTrue(initialDelayOf("z") == 0L)
  }
}
