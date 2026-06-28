package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.serialization.json.JsonElement

interface Tool {
  val name: String
  val description: String
  val schema: FunctionSchema
  suspend fun execute(arguments: Map<String, JsonElement>): ToolResult
}
