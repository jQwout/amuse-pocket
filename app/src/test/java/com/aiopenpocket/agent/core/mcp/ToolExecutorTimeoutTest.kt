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

/** Тул, который «висит» ровно [hangMs] виртуальных миллисекунд. */
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

/** Тул, который висит НАВСЕГДА и при этом глотает отмену, как это делали тулы до фикса. */
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
 * У выполнения инструмента должен быть потолок по времени.
 *
 * [ToolExecutor] звал `tool.execute` без таймаута, а тулы ходят в ContentResolver, Room и системные
 * сервисы — любой залипший вызов держал ход агента бесконечно: стрим не завершался, статус оставался
 * Running, и вывести из этого мог только внешний cancel всего хода. Тул, который не ответил за
 * отведённое время, обязан вернуть модели ошибку, а не остановить ход.
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
