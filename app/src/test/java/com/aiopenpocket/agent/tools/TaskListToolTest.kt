package com.aiopenpocket.agent.tools

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.tasks.TaskBoard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskListToolTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun file() = File(tmp.root, "tasks.json")
  private fun args(vararg p: Pair<String, String>): Map<String, JsonElement> = p.associate { it.first to JsonPrimitive(it.second) }
  private fun text(r: ToolResult) = (r as ToolResult.Success).data

  @Test
  fun `add then list shows the task in a new list and survives a restart`() = runTest {
    val tool = TaskListToolCore(TaskBoard(file()))
    text(tool.execute(args("action" to "add", "title" to "buy milk", "list" to "Shopping", "priority" to "high")))
    assertTrue(text(tool.execute(args("action" to "list"))).contains("[Shopping] buy milk"))
    // A second board over the same file is what the screen sees after the app restarts.
    val again = TaskListToolCore(TaskBoard(file()))
    assertTrue(text(again.execute(args("action" to "list"))).contains("buy milk, priority high"))
  }

  @Test
  fun `complete hides the task unless include_done and delete removes it`() = runTest {
    val tool = TaskListToolCore(TaskBoard(file()))
    text(tool.execute(args("action" to "add", "title" to "call mom")))
    val id = "2"
    text(tool.execute(args("action" to "complete", "id" to id)))
    assertEquals("No tasks.", text(tool.execute(args("action" to "list"))))
    assertTrue(text(tool.execute(args("action" to "list", "include_done" to "true"))).contains("(done)"))
    text(tool.execute(args("action" to "delete", "id" to id)))
    assertEquals("No tasks.", text(tool.execute(args("action" to "list", "include_done" to "true"))))
  }

  @Test
  fun `update changes the due date and none clears it`() = runTest {
    val tool = TaskListToolCore(TaskBoard(file()))
    tool.execute(args("action" to "add", "title" to "report"))
    tool.execute(args("action" to "update", "id" to "2", "due" to "2030-05-01"))
    assertTrue(text(tool.execute(args("action" to "list"))).contains("due 2030-05-01"))
    tool.execute(args("action" to "update", "id" to "2", "due" to "none"))
    assertTrue(!text(tool.execute(args("action" to "list"))).contains("due"))
  }

  @Test
  fun `bad input is an error not a crash`() = runTest {
    val tool = TaskListToolCore(TaskBoard(file()))
    assertTrue(tool.execute(args("action" to "add", "title" to "x", "due" to "tomorrow")) is ToolResult.Error)
    assertTrue(tool.execute(args("action" to "complete", "id" to "99")) is ToolResult.Error)
    assertTrue(tool.execute(args("action" to "add")) is ToolResult.Error)
  }

  @Test
  fun `only delete is destructive`() {
    val tool = TaskListToolCore(TaskBoard(file()))
    assertEquals(RiskClass.DESTRUCTIVE, tool.riskFor(args("action" to "delete")))
    assertEquals(RiskClass.WRITE, tool.riskFor(args("action" to "add")))
    assertEquals(RiskClass.WRITE, tool.riskFor(args("action" to "complete")))
    assertEquals(RiskClass.READ, tool.riskFor(args("action" to "list")))
  }

  @Test
  fun `a damaged file starts an empty list instead of crashing`() = runTest {
    file().writeText("{ not json")
    val tool = TaskListToolCore(TaskBoard(file()))
    assertEquals("No tasks.", text(tool.execute(args("action" to "list"))))
  }
}
