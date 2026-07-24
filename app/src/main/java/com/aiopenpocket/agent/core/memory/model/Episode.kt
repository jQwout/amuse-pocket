package com.aiopenpocket.agent.core.memory.model

// @trace agent-memory-05e545

data class Episode(
  val id: String,
  val type: String,
  val payload: String,
  val outcome: String,
  val timestamp: Long,
  val conversationId: String?,
)
