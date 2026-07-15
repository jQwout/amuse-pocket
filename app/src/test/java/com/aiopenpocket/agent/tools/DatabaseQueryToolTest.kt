package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security regression for the Part A hardening of `database_query` (`user-agent-builder-848153`):
 * SELECT-only, restricted to a fixed table allowlist, enforced IN CODE — a system prompt could say
 * "only read messages/facts", but nothing stops a model from ignoring that, so the enforcement has to
 * live in [DatabaseQueryTool] itself, not in prompt wording (mirrors
 * [MemoryToolsNamespaceTest]'s framing for the same reason).
 *
 * Runs every case through [DatabaseQueryTool.execute] against a real (in-memory) Room database, never
 * against the tool's private parsing helpers directly — these tests fail the exact same way a real
 * attempt through the tool would, and stay meaningful even if the parsing internals are refactored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseQueryToolTest {

  private lateinit var db: AppDatabase
  private lateinit var tool: DatabaseQueryTool

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    tool = DatabaseQueryTool(db)
  }

  @After
  fun tearDown() = db.close()

  private fun sql(query: String): Map<String, JsonElement> = mapOf("sql" to JsonPrimitive(query))

  private suspend fun expectAllowed(query: String) {
    val result = tool.execute(sql(query))
    assertTrue("expected '$query' to be ALLOWED, got: $result", result is ToolResult.Success)
  }

  private suspend fun expectRejected(query: String) {
    val result = tool.execute(sql(query))
    assertTrue("expected '$query' to be REJECTED, got: $result", result is ToolResult.Error)
  }

  // --- allowed: SELECT on an allowlisted table -------------------------------------------------

  @Test
  fun `SELECT on an allowed table passes`() = runTest {
    expectAllowed("SELECT * FROM messages")
  }

  @Test
  fun `SELECT on an allowed table with a LIMIT passes`() = runTest {
    expectAllowed("SELECT id, content FROM messages ORDER BY createdAt DESC LIMIT 5")
  }

  @Test
  fun `SELECT with an explicit main schema prefix still resolves the allowed table`() = runTest {
    expectAllowed("SELECT * FROM main.messages")
  }

  @Test
  fun `SELECT joining two allowed tables passes`() = runTest {
    expectAllowed("SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId")
  }

  @Test
  fun `SELECT with an old-style comma join between two allowed, aliased tables passes`() = runTest {
    // Regression for the FROM-list comma-splitting in extractTables: "FROM t1 a1, t2 a2" must resolve
    // to {t1, t2}, not silently drop t2 because it never follows a literal FROM/JOIN keyword directly.
    expectAllowed("SELECT * FROM messages m, conversations c WHERE m.conversationId = c.id")
  }

  @Test
  fun `uppercase SELECT still passes`() = runTest {
    expectAllowed("select * from facts")
  }

  // --- rejected: forbidden system/control tables (direct, JOIN, subquery, CTE) ----------------

  @Test
  fun `SELECT referencing user_agents directly is rejected`() = runTest {
    expectRejected("SELECT * FROM user_agents")
  }

  @Test
  fun `SELECT referencing agent_hooks directly is rejected`() = runTest {
    expectRejected("SELECT * FROM agent_hooks")
  }

  @Test
  fun `SELECT referencing agent_hook_runs directly is rejected`() = runTest {
    expectRejected("SELECT * FROM agent_hook_runs")
  }

  @Test
  fun `SELECT referencing heartbeats directly is rejected`() = runTest {
    expectRejected("SELECT * FROM heartbeats")
  }

  @Test
  fun `SELECT referencing a forbidden table via subquery is rejected`() = runTest {
    expectRejected("SELECT * FROM messages WHERE conversationId IN (SELECT id FROM user_agents)")
  }

  @Test
  fun `SELECT referencing a forbidden table via JOIN is rejected`() = runTest {
    expectRejected("SELECT m.* FROM messages m JOIN user_agents u ON u.id = m.conversationId")
  }

  @Test
  fun `a CTE is rejected outright by the SELECT-only gate, even one that only wraps a forbidden table`() = runTest {
    // This tool never supports CTEs at all - "WITH ..." has firstSqlToken "with", not "select", so
    // rejectionReason's SELECT-only gate refuses it before containsForbiddenToken/extractTables ever
    // run. Still a meaningful regression: proves a CTE cannot be used to route around the allowlist.
    expectRejected("WITH x AS (SELECT * FROM user_agents) SELECT * FROM x")
  }

  @Test
  fun `SELECT referencing a forbidden table only inside a string literal is still rejected (belt-and-suspenders over-blocks, safely)`() = runTest {
    // Documents the accepted trade-off in DatabaseQueryTool.containsForbiddenToken's kdoc: a false
    // rejection here is safe, a false allow is not.
    expectRejected("SELECT * FROM messages WHERE content = 'heartbeats'")
  }

  @Test
  fun `an old-style comma join smuggling a forbidden table as the second table is rejected`() = runTest {
    // A single-capture FROM/JOIN regex could plausibly see only "messages" here and miss
    // "user_agents" entirely, since it never follows a literal FROM/JOIN keyword of its own -
    // containsForbiddenToken AND the comma-aware extractTables both independently catch it.
    expectRejected("SELECT * FROM messages, user_agents")
  }

  @Test
  fun `an old-style comma join smuggling an unknown (not merely forbidden-named) table is still rejected`() = runTest {
    // Unlike the previous test, containsForbiddenToken cannot help here - "some_unknown_table" is not
    // one of the four named FORBIDDEN_TABLES. Only extractTables' comma-splitting (checking BOTH
    // comma-separated tables against the READABLE_TABLES allowlist) catches this one.
    expectRejected("SELECT * FROM messages, some_unknown_table")
  }

  // --- rejected: writes are no longer reachable at all (Part A) --------------------------------

  @Test
  fun `INSERT is rejected`() = runTest {
    expectRejected("INSERT INTO facts (id, key, value, category) VALUES ('1','k','v','general')")
  }

  @Test
  fun `UPDATE is rejected`() = runTest {
    expectRejected("UPDATE facts SET value = 'x' WHERE key = 'y'")
  }

  @Test
  fun `DELETE is rejected`() = runTest {
    expectRejected("DELETE FROM facts WHERE key = 'y'")
  }

  @Test
  fun `DDL is rejected`() = runTest {
    expectRejected("DROP TABLE messages")
  }

  @Test
  fun `multi-statement calls are rejected even when both statements target allowed tables`() = runTest {
    expectRejected("SELECT * FROM messages; SELECT * FROM facts")
  }

  // --- rejected: fail-closed parsing -------------------------------------------------------------

  @Test
  fun `a table-less statement is rejected fail-closed`() = runTest {
    expectRejected("SELECT 1")
  }

  @Test
  fun `a derived-table-only FROM with no named table anywhere is rejected fail-closed`() = runTest {
    expectRejected("SELECT * FROM (SELECT 1)")
  }

  @Test
  fun `a real table absent from the allowlist is rejected`() = runTest {
    expectRejected("SELECT * FROM sqlite_master")
  }

  @Test
  fun `an unknown made-up table name is rejected`() = runTest {
    expectRejected("SELECT * FROM some_random_table")
  }
}
