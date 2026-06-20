package com.aiopenpocket.agent.core.mcp

import kotlinx.serialization.json.JsonElement

sealed class ToolResult {
  data class Success(val data: String) : ToolResult()
  data class Error(val message: String, val recoverable: Boolean = true) : ToolResult()

  override fun toString(): String = when (this) {
    is Success -> data
    is Error -> "ERROR: $message"
  }
}
