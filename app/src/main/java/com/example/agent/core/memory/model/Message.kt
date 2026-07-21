package com.aiopenpocket.agent.core.memory.model

// @trace multi-chat-conversations-b3c93b

data class Message(
  val id: String,
  val conversationId: String,
  val role: String, // "user", "assistant", "system", "tool"
  val content: String,
  val toolCallsJson: String?,
  val cardSpec: String? = null,
  val createdAt: Long,
)
