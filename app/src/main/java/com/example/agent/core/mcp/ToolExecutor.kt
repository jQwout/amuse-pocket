package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject

@Inject
class ToolExecutor(
  private val registry: ToolRegistry,
) {
  suspend fun execute(name: String, arguments: Map<String, JsonElement>): ToolResult {
    val tool = registry.get(name)
      ?: return ToolResult.Error("Tool '$name' not found", recoverable = false)
    // Validate against the declared schema once here, so tools don't each re-check required/enum.
    SchemaValidator.validate(tool.schema, arguments)?.let { return it }
    return try {
      tool.execute(arguments)
    } catch (e: CancellationException) {
      // Отмена запроса (юзер нажал Stop) — не глотаем, иначе цикл агента поедет дальше.
      throw e
    } catch (e: Exception) {
      ToolResult.Error("Execution failed: ${e.message}", recoverable = true)
    }
  }
}
