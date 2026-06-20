package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "episodes",
  indices = [Index("timestamp"), Index("type")],
)
data class EpisodeEntity(
  @PrimaryKey val id: String,
  val type: String,
  val payload: String,
  val outcome: String,
  val timestamp: Long = System.currentTimeMillis(),
  val conversationId: String? = null,
)
