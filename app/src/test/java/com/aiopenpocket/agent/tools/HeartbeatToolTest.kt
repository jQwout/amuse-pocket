package com.aiopenpocket.agent.tools

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.plan.schedule.WorkManagerHeartbeatScheduler
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatToolTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: HeartbeatTool

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // POST_NOTIFICATIONS: Robolectric по умолчанию НЕ выдаёт runtime-разрешение — выдаём явно.
    shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    holder = TurnContextHolder().apply { set("conv1", testAgent("reminders")) }
    // Task 11: WorkManager mechanics moved out of HeartbeatTool into HeartbeatScheduler — the REAL
    // WorkManagerHeartbeatScheduler is used here (not a fake) since WorkManagerTestInitHelper above
    // already makes a real WorkManager instance safe to drive in Robolectric, preserving this test's
    // original coverage of the actual schedule/cancel plumbing.
    tool = HeartbeatTool(context, db.heartbeatDao(), holder, WorkManagerHeartbeatScheduler(context))
  }

  @After
  fun tearDown() = db.close()

  /** Minimal valid [AgentDefinition] — only [AgentDefinition.id] matters to this test. */
  private fun testAgent(id: String) = AgentDefinition(
    id = id,
    source = AgentSource.PRESET,
    name = id,
    description = "test agent",
    iconKey = "sparkles",
    systemPrompt = "test",
    toolNames = emptySet(),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  @Test
  fun `schedule push one-time writes row and returns id`() = runTest {
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "Позвонить маме",
        "message" to "Позвони!", "delay_minutes" to "30"),
    )
    assertTrue(result is ToolResult.Success)
    val row = db.heartbeatDao().listActive().single()
    assertEquals("push", row.mode)
    assertEquals("one_time", row.kind)
    assertEquals("conv1", row.conversationId)
    assertTrue((result as ToolResult.Success).data.contains(row.id))
    assertTrue(result.data.contains("render_card"))
  }

  @Test
  fun `schedule agent periodic stores prompt and agentId`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "agent", "title" to "Пульс задач",
        "prompt" to "проверь задачи", "interval_minutes" to "30"),
    )
    val row = db.heartbeatDao().listActive().single()
    assertEquals("agent", row.mode)
    assertEquals("periodic", row.kind)
    assertEquals("проверь задачи", row.prompt)
    assertEquals("reminders", row.agentId)
    assertEquals(30L, row.intervalMinutes)
  }

  @Test
  fun `periodic interval below 15 is rejected`() = runTest {
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "interval_minutes" to "5"),
    )
    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `schedule without delay and interval is rejected`() = runTest {
    val result = tool.execute(args("action" to "schedule", "mode" to "push", "title" to "x"))
    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `cancel sets status and returns success`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "delay_minutes" to "10"),
    )
    val id = db.heartbeatDao().listActive().single().id
    val result = tool.execute(args("action" to "cancel", "heartbeat_id" to id))
    assertTrue(result is ToolResult.Success)
    assertEquals("cancelled", db.heartbeatDao().getById(id)?.status)
  }

  /** Агент не видит и не отменяет напоминания другого агента: у каждого из ростера свои. */
  @Test
  fun `an agent cannot cancel or list another agent's heartbeat`() = runTest {
    holder.set("conv1", testAgent("tasks"))
    tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "Чужое напоминание",
        "message" to "y", "delay_minutes" to "10"),
    )
    val id = db.heartbeatDao().listActive().single().id
    holder.set("conv1", testAgent("reminders"))

    assertTrue(tool.execute(args("action" to "cancel", "heartbeat_id" to id)) is ToolResult.Error)
    assertEquals("scheduled", db.heartbeatDao().getById(id)?.status)
    val listed = tool.execute(args("action" to "list")) as ToolResult.Success
    assertFalse(listed.data.contains("Чужое напоминание"))
  }

  @Test
  fun `list returns active heartbeats`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "Уникальный заголовок",
        "message" to "y", "delay_minutes" to "10"),
    )
    val result = tool.execute(args("action" to "list"))
    assertTrue((result as ToolResult.Success).data.contains("Уникальный заголовок"))
  }

  @Test
  fun `schedule without notification permission returns recoverable error`() = runTest {
    shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "delay_minutes" to "10"),
    )
    assertTrue(result is ToolResult.Error)
    assertTrue((result as ToolResult.Error).message.contains("notification_permission"))
  }
}
