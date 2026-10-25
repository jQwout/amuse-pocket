package com.aiopenpocket.agent.tools

// @trace background-tasks-15a34a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.workers.NotificationWorker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * `work_manager.cancel_work` отменяет только задачи, созданные этим же тулом (тег `id:<work_id>`).
 * Раньше он принимал любое уникальное имя — и модель могла снести напоминание `heartbeat_<id>` в
 * обход HeartbeatTool: строка в Room осталась бы «scheduled», а пуш так и не пришёл бы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerToolTest {

  private lateinit var context: Context
  private lateinit var tool: WorkManagerTool

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    tool = WorkManagerTool(context)
  }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun stateOf(uniqueName: String): WorkInfo.State =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().single().state

  @Test
  fun `cancel_work cancels a task this tool scheduled`() = runTest {
    val scheduled = tool.execute(
      args("action" to "schedule_notification", "delay_minutes" to "60", "title" to "Вода", "message" to "Выпей воды"),
    ) as ToolResult.Success
    val workId = scheduled.data.substringAfter("Work ID: ").trim()

    val result = tool.execute(args("action" to "cancel_work", "work_id" to workId))

    assertTrue(result is ToolResult.Success)
    assertEquals(WorkInfo.State.CANCELLED, stateOf(workId))
  }

  @Test
  fun `cancel_work refuses a heartbeat reminder it did not create`() = runTest {
    val heartbeatWork = HeartbeatTool.workName("hb-1")
    WorkManager.getInstance(context).enqueueUniqueWork(
      heartbeatWork,
      ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequestBuilder<NotificationWorker>().setInitialDelay(1, TimeUnit.HOURS).build(),
    )

    val result = tool.execute(args("action" to "cancel_work", "work_id" to heartbeatWork))

    assertTrue("a foreign unique name must be refused", result is ToolResult.Error)
    assertEquals("the reminder must stay scheduled", WorkInfo.State.ENQUEUED, stateOf(heartbeatWork))
  }

  @Test
  fun `cancel_work on an unknown id is an error, not a fake success`() = runTest {
    val result = tool.execute(args("action" to "cancel_work", "work_id" to "never-scheduled"))

    assertTrue(result is ToolResult.Error)
  }
}
