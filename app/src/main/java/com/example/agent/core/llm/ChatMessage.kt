package com.aiopenpocket.agent.core.llm

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
  val role: String,
  val content: String? = null,
  val toolCalls: List<ToolCallData>? = null,
  val toolCallId: String? = null,
) {
  @Serializable
  data class ToolCallData(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
  ) {
    @Serializable
    data class FunctionCall(
      val name: String,
      val arguments: String,
    )
  }
}
