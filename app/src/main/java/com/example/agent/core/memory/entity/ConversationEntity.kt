package com.aiopenpocket.agent.core.memory.entity

// @trace multi-chat-conversations-b3c93b

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val personaId: String = "sharp_partner",
  val summary: String? = null,
  val isTutorial: Boolean = false,
  /** Bound mini-agent id (scopes tools + role); null = general assistant. */
  val agentId: String? = null,
)
