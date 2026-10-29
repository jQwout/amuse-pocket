package com.aiopenpocket.agent.core.mcp

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool arguments that were not valid JSON must come back to the model as exactly that. */
class ToolExecutorMalformedArgumentsTest {

  private class SpyTool : Tool {
    override val name = "add_note"
    override val description = "d"
    override val schema = FunctionSchema(
      function = FunctionSchema.FunctionDefinition(
        name = "add_note",
        description = "d",
        parameters = buildJsonObject {
          put("type", JsonPrimitive("object"))
          putJsonObject("properties") { putJsonObject("title") { put("type", JsonPrimitive("string")) } }
          put("required", buildJsonArray { add(JsonPrimitive("title")) })
        },
      ),
    )
    var executed = false

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
      executed = true
      return ToolResult.Success("ok")
    }
  }

  @Test
  fun `broken JSON is reported as broken JSON, not as a missing argument`() = runTest {
    val tool = SpyTool()
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))

    val result = executor.execute("add_note", MalformedArguments.marker("{\"title\": \"Buy milk"))

    val error = result as ToolResult.Error
    assertTrue(error.message, error.message.contains("not valid JSON"))
    assertTrue("the excerpt shows the model what it sent: ${error.message}", error.message.contains("{\"title\": \"Buy milk"))
    assertTrue("asks for a resend: ${error.message}", error.message.contains("Resend"))
    assertFalse("must not blame the schema: ${error.message}", error.message.contains("required"))
    assertTrue(error.recoverable)
    assertFalse(tool.executed)
  }

  @Test
  fun `the excerpt is short and on one line`() {
    val marker = MalformedArguments.marker("{\"a\":\n" + "x".repeat(5_000))
    val excerpt = MalformedArguments.excerptOf(marker)!!
    assertTrue(excerpt.length < 200)
    assertFalse(excerpt.contains('\n'))
  }

  @Test
  fun `real arguments are not mistaken for a marker`() = runTest {
    val tool = SpyTool()
    val executor = ToolExecutor(ToolRegistry(setOf(tool)))
    assertEquals(ToolResult.Success("ok"), executor.execute("add_note", mapOf("title" to JsonPrimitive("x"))))
  }
}
