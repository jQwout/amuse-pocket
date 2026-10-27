package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tool that "hangs" for exactly [hangMs] virtual milliseconds. */
private class SlowTool(private val hangMs: Long) : Tool {
  override val name = "slow_tool"
  override val description = "виснет"
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(name = name, description = description, parameters = JsonObject(emptyMap())),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    delay(hangMs)
    return ToolResult.Success("наконец-то")
  }
}

/** A tool that hangs FOREVER and swallows cancellation too, as tools did before the fix. */
private class UncancellableTool : Tool {
  override val name = "stuck_tool"
  override val description = "виснет навсегда"
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(name = name, description = description, parameters = JsonObject(emptyMap())),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    CompletableDeferred<Unit>().await()
    return ToolResult.Success("недостижимо")
  }
}

/**
 * Running a tool must have a time ceiling.
 *
 * [ToolExecutor] called `tool.execute` without a timeout, and tools go to ContentResolver, Room and system
 * services — any stuck call held the agent's turn forever: the stream did not end, the status stayed
 * Running, and only an external cancel of the whole turn could get out of it. A tool that did not answer in
 * the allotted time must return an error to the model, not stop the turn.
 */
class ToolExecutorTimeoutTest {

  private fun executorWith(tool: Tool) = ToolExecutor(ToolRegistry(setOf(tool)))

  @Test
  fun `a tool that hangs past the budget returns an error instead of stalling the turn`() = runTest {
    val executor = executorWith(SlowTool(hangMs = ToolExecutor.TOOL_TIMEOUT_MS + 1_000))

    val result = executor.execute("slow_tool", emptyMap())

    assertTrue("ожидали Error, получили $result", result is ToolResult.Error)
  }

  @Test
  fun `the timeout error names the tool so the model can react`() = runTest {
    val executor = executorWith(SlowTool(hangMs = ToolExecutor.TOOL_TIMEOUT_MS + 1_000))

    val result = executor.execute("slow_tool", emptyMap())

    assertTrue("невнятная ошибка: $result", result.toString().contains("slow_tool"))
  }

  @Test
  fun `a tool finishing inside the budget is untouched`() = runTest {
    val executor = executorWith(SlowTool(hangMs = ToolExecutor.TOOL_TIMEOUT_MS - 1_000))

    assertEquals(ToolResult.Success("наконец-то"), executor.execute("slow_tool", emptyMap()))
  }

  @Test
  fun `a tool suspended forever is bounded too, not just a slow one`() = runTest {
    val executor = executorWith(UncancellableTool())

    val result = executor.execute("stuck_tool", emptyMap())

    assertTrue("ожидали Error, получили $result", result is ToolResult.Error)
  }
}
