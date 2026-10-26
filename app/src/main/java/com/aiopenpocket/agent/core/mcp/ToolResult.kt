package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import kotlinx.serialization.json.JsonElement

sealed class ToolResult {
  /**
   * [simulated] — the call never ran: PREVIEW handed the model a synthetic result. The trace shows it as
   * "simulated", otherwise a builder dry run read "Mail: compose → ok" as if the email was real.
   */
  data class Success(val data: String, val simulated: Boolean = false) : ToolResult()
  data class Error(val message: String, val recoverable: Boolean = true) : ToolResult()

  override fun toString(): String = when (this) {
    is Success -> data
    is Error -> "ERROR: $message"
  }
}
