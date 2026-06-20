package com.aiopenpocket.agent.core.mcp

import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject

class ToolExecutor @Inject constructor(
  private val registry: ToolRegistry,
) {
  suspend fun execute(name: String, arguments: Map<String, JsonElement>): ToolResult {
    val tool = registry.get(name)
      ?: return ToolResult.Error("Tool '$name' not found", recoverable = false)
    return try {
      tool.execute(arguments)
    } catch (e: Exception) {
      ToolResult.Error("Execution failed: ${e.message}", recoverable = true)
    }
  }
}
