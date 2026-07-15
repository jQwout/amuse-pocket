package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.schedule.WorkManagerHeartbeatScheduler
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Table-driven coverage for [com.aiopenpocket.agent.core.mcp.Tool.riskFor] (Task 9) on every
 * mixed-action tool the design brief names: file_tool, external_files, memory_tool, calendar,
 * database_query, heartbeat, work_manager. Each maps a DIFFERENT [RiskClass] per action/statement —
 * this is exactly what `com.aiopenpocket.agent.core.policy.ExecutionPolicy` gates PREVIEW-mode
 * simulation and approval on, so a wrong mapping here is a real safety-classification bug, not
 * cosmetics. database_query is the one exception since Part A's SELECT-only hardening
 * (`user-agent-builder-848153`): it no longer varies by verb (always [RiskClass.READ] — see that
 * section below) because every verb besides SELECT is now rejected outright rather than merely
 * risk-classified differently; kept in this suite anyway as a regression guard against that
 * classification ever silently drifting back.
 *
 * [riskFor] never touches any of these tools' injected dependencies (it only parses `arguments`),
 * but most of their constructors need a real Context/Room to build at all — one Robolectric fixture
 * for all seven keeps this focused instead of needing seven separate test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolRiskForTest {

  private lateinit var db: AppDatabase
  private lateinit var fileTools: FileTools
  private lateinit var externalFilesTool: ExternalFilesTool
  private lateinit var memoryTools: MemoryTools
  private lateinit var calendarTool: CalendarTool
  private lateinit var databaseQueryTool: DatabaseQueryTool
  private lateinit var heartbeatTool: HeartbeatTool
  private lateinit var workManagerTool: WorkManagerTool

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val application = ApplicationProvider.getApplicationContext<Application>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val holder = TurnContextHolder()
    fileTools = FileTools(application, holder)
    externalFilesTool = ExternalFilesTool(context)
    memoryTools = MemoryTools(MemoryRepository(db.factDao(), db.episodeDao()), holder)
    calendarTool = CalendarTool(context)
    databaseQueryTool = DatabaseQueryTool(db)
    // Task 11: HeartbeatTool now needs a HeartbeatScheduler too — riskFor() never touches it (see
    // this class's own kdoc), so the real WorkManagerHeartbeatScheduler is fine to construct here
    // (its constructor does not itself touch WorkManager.getInstance()).
    heartbeatTool = HeartbeatTool(context, db.heartbeatDao(), holder, WorkManagerHeartbeatScheduler(context))
    workManagerTool = WorkManagerTool(context)
  }

  @After
  fun tearDown() = db.close()

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  // --- file_tool: read_file/list_dir/search_files=READ, write_file=WRITE ----------------------

  @Test
  fun `file_tool read_file is READ`() {
    assertEquals(RiskClass.READ, fileTools.riskFor(args("action" to "read_file")))
  }

  @Test
  fun `file_tool list_dir is READ`() {
    assertEquals(RiskClass.READ, fileTools.riskFor(args("action" to "list_dir")))
  }

  @Test
  fun `file_tool search_files is READ`() {
    assertEquals(RiskClass.READ, fileTools.riskFor(args("action" to "search_files")))
  }

  @Test
  fun `file_tool write_file is WRITE`() {
    assertEquals(RiskClass.WRITE, fileTools.riskFor(args("action" to "write_file")))
  }

  // --- external_files: same read/write split as file_tool -------------------------------------

  @Test
  fun `external_files read_file is READ`() {
    assertEquals(RiskClass.READ, externalFilesTool.riskFor(args("action" to "read_file")))
  }

  @Test
  fun `external_files list_dir is READ`() {
    assertEquals(RiskClass.READ, externalFilesTool.riskFor(args("action" to "list_dir")))
  }

  @Test
  fun `external_files write_file is WRITE`() {
    assertEquals(RiskClass.WRITE, externalFilesTool.riskFor(args("action" to "write_file")))
  }

  // --- memory_tool: search_memory/recall_episodes=READ, remember_fact=WRITE -------------------

  @Test
  fun `memory_tool search_memory is READ`() {
    assertEquals(RiskClass.READ, memoryTools.riskFor(args("action" to "search_memory")))
  }

  @Test
  fun `memory_tool recall_episodes is READ`() {
    assertEquals(RiskClass.READ, memoryTools.riskFor(args("action" to "recall_episodes")))
  }

  @Test
  fun `memory_tool remember_fact is WRITE`() {
    assertEquals(RiskClass.WRITE, memoryTools.riskFor(args("action" to "remember_fact")))
  }

  // --- calendar: list_events=READ, create_event=WRITE, delete_event=DESTRUCTIVE ----------------

  @Test
  fun `calendar list_events is READ`() {
    assertEquals(RiskClass.READ, calendarTool.riskFor(args("action" to "list_events")))
  }

  @Test
  fun `calendar create_event is WRITE`() {
    assertEquals(RiskClass.WRITE, calendarTool.riskFor(args("action" to "create_event")))
  }

  @Test
  fun `calendar delete_event is DESTRUCTIVE`() {
    assertEquals(RiskClass.DESTRUCTIVE, calendarTool.riskFor(args("action" to "delete_event")))
  }

  // --- database_query: Part A hardening (user-agent-builder-848153) made it SELECT-only, so every
  // call - whatever verb the `sql` argument names - now classifies as READ. INSERT/UPDATE/DELETE are
  // no longer just risk-classified differently, they are REJECTED OUTRIGHT by execute() before ever
  // running (see DatabaseQueryToolTest for that rejection behavior); riskFor's job here is only to
  // prove the classification itself never drifts back to WRITE/DESTRUCTIVE for any input shape.

  @Test
  fun `database_query SELECT is READ`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "SELECT * FROM messages")))
  }

  @Test
  fun `database_query lowercase select is still READ`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "select * from messages")))
  }

  @Test
  fun `database_query INSERT classifies as READ - execute() rejects it outright, it can never actually write`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "INSERT INTO facts(key) VALUES ('x')")))
  }

  @Test
  fun `database_query UPDATE classifies as READ - execute() rejects it outright, it can never actually write`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "UPDATE facts SET value='x' WHERE key='y'")))
  }

  @Test
  fun `database_query DELETE classifies as READ - execute() rejects it outright, it can never actually write`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "DELETE FROM facts WHERE key='y'")))
  }

  @Test
  fun `database_query does not classify by naive substring - a SELECT merely mentioning delete in a literal stays READ`() {
    // Regression for "reuse the existing parser, don't classify by naive substring": a real
    // .contains("delete", ignoreCase = true) check would misclassify this SELECT as DESTRUCTIVE.
    assertEquals(
      RiskClass.READ,
      databaseQueryTool.riskFor(args("sql" to "SELECT * FROM messages WHERE content LIKE '%delete%'")),
    )
  }

  @Test
  fun `database_query forbidden DDL classifies as READ so execute()'s real rejection still surfaces in PREVIEW`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(args("sql" to "DROP TABLE messages")))
  }

  @Test
  fun `database_query missing sql falls back to the tool's base riskClass`() {
    assertEquals(RiskClass.READ, databaseQueryTool.riskFor(emptyMap()))
  }

  // --- heartbeat: list=READ, schedule=WRITE, cancel=DESTRUCTIVE -------------------------------

  @Test
  fun `heartbeat list is READ`() {
    assertEquals(RiskClass.READ, heartbeatTool.riskFor(args("action" to "list")))
  }

  @Test
  fun `heartbeat schedule is WRITE`() {
    assertEquals(RiskClass.WRITE, heartbeatTool.riskFor(args("action" to "schedule")))
  }

  @Test
  fun `heartbeat cancel is DESTRUCTIVE`() {
    assertEquals(RiskClass.DESTRUCTIVE, heartbeatTool.riskFor(args("action" to "cancel")))
  }

  // --- work_manager: schedule_notification/schedule_periodic=WRITE, cancel_work=DESTRUCTIVE ---

  @Test
  fun `work_manager schedule_notification is WRITE`() {
    assertEquals(RiskClass.WRITE, workManagerTool.riskFor(args("action" to "schedule_notification")))
  }

  @Test
  fun `work_manager schedule_periodic is WRITE`() {
    assertEquals(RiskClass.WRITE, workManagerTool.riskFor(args("action" to "schedule_periodic")))
  }

  @Test
  fun `work_manager cancel_work is DESTRUCTIVE`() {
    assertEquals(RiskClass.DESTRUCTIVE, workManagerTool.riskFor(args("action" to "cancel_work")))
  }
}
