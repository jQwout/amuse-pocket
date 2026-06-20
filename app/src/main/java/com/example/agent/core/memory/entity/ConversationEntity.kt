package com.aiopenpocket.agent.core.memory.entity

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
)
