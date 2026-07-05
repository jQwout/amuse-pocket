package com.aiopenpocket.agent.core.mcp

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema with one required string param `action` constrained to enum {read, write}. */
private fun actionSchema(): FunctionSchema = FunctionSchema(
  function = FunctionSchema.FunctionDefinition(
    name = "act",
    description = "d",
    parameters = buildJsonObject {
      put("type", JsonPrimitive("object"))
      putJsonObject("properties") {
        putJsonObject("action") {
          put("type", JsonPrimitive("string"))
          put("enum", buildJsonArray { add(JsonPrimitive("read")); add(JsonPrimitive("write")) })
        }
      }
      put("required", buildJsonArray { add(JsonPrimitive("action")) })
    },
  ),
)

/** Tool that records whether its body actually ran, so we can assert the gate blocks execution. */
private class RecordingTool(override val schema: FunctionSchema) : Tool {
  override val name = schema.function.name
  override val description = "d"
  var executed = false
    private set

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    executed = true
    return ToolResult.Success("ok")
  }
}

private class FakeTool(
  override val name: String,
  private val onExecute: () -> ToolResult,
) : Tool {
  override val description = "fake tool"
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(emptyMap()),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = onExecute()
}

class ToolExecutorTest {

  @Test
  fun `unknown tool returns non-recoverable error`() = runTest {
    val executor = ToolExecutor(ToolRegistry(emptySet()))
    val result = executor.execute("nope", emptyMap())
    assertTrue(result is ToolResult.Error)
    assertFalse((result as ToolResult.Error).recoverable)
  }

  @Test
  fun `successful tool result is passed through`() = runTest {
    val tool = FakeTool("echo") { ToolResult.Success("done") }
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    val result = executor.execute("echo", emptyMap())
    assertEquals(ToolResult.Success("done"), result)
  }

  @Test
  fun `tool throwing is wrapped as recoverable error`() = runTest {
    val tool = FakeTool("boom") { throw RuntimeException("kaboom") }
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    val result = executor.execute("boom", emptyMap())
    assertTrue(result is ToolResult.Error)
    assertTrue((result as ToolResult.Error).recoverable)
    assertTrue(result.message.contains("kaboom"))
  }

  @Test
  fun `missing required argument is rejected before the tool runs`() = runTest {
    val tool = RecordingTool(actionSchema())
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    val result = executor.execute("act", emptyMap())
    assertTrue(result is ToolResult.Error)
    assertFalse((result as ToolResult.Error).recoverable)
    assertTrue(result.message.contains("action"))
    assertFalse("tool must not run when validation fails", tool.executed)
  }

  @Test
  fun `enum violation is rejected before the tool runs`() = runTest {
    val tool = RecordingTool(actionSchema())
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    val result = executor.execute("act", mapOf("action" to JsonPrimitive("delete")))
    assertTrue(result is ToolResult.Error)
    assertFalse(tool.executed)
  }

  @Test
  fun `valid arguments pass validation and reach the tool`() = runTest {
    val tool = RecordingTool(actionSchema())
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    val result = executor.execute("act", mapOf("action" to JsonPrimitive("read")))
    assertEquals(ToolResult.Success("ok"), result)
    assertTrue(tool.executed)
  }

  @Test
  fun `registry exposes registered tools and schemas`() {
    val registry = ToolRegistry(setOf(FakeTool("a") { ToolResult.Success("") }, FakeTool("b") { ToolResult.Success("") }))
    assertEquals(setOf("a", "b"), registry.getAll().map { it.name }.toSet())
    assertEquals(2, registry.getAllSchemas().size)
    assertEquals("a", registry.get("a")?.name)
    assertEquals(null, registry.get("missing"))
  }
}
