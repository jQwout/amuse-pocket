package com.aiopenpocket.agent.core.llm

import kotlinx.serialization.json.JsonElement

sealed class StreamEvent {
  data class TextDelta(val content: String) : StreamEvent()
  data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
  ) : StreamEvent()
  data class Finish(val reason: String) : StreamEvent()
  data class Error(val message: String, val code: String? = null) : StreamEvent()
}
