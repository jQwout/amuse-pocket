package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatMessage(
  val role: String,
  val content: String? = null,
  val toolCalls: List<ToolCallData>? = null,
  val toolCallId: String? = null,
  /**
   * A photo for this user message (photos-in-chat-bca88e): a path, not the picture. Kept out of the
   * JSON: the client turns it into an `image_url` content part only when it builds the request.
   */
  @Transient val imagePath: String? = null,
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
