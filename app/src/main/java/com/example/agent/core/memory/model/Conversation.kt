package com.aiopenpocket.agent.core.memory.model

data class Conversation(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val personaId: String,
  val summary: String?,
  val isTutorial: Boolean = false,
  val tags: List<String> = emptyList(),
  /** Bound mini-agent id (scopes tools + role); null = general assistant. */
  val agentId: String? = null,
)
