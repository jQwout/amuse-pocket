package com.aiopenpocket.agent.core.memory.entity

// @trace multi-chat-conversations-b3c93b

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "messages",
  foreignKeys = [
    ForeignKey(
      entity = ConversationEntity::class,
      parentColumns = ["id"],
      childColumns = ["conversationId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String, // "user", "assistant", "system", "tool"
  val content: String,
  val toolCallsJson: String? = null,
  /** Driven UI card spec ({"template":...,"params":...}) rendered as markdown in chat. */
  val cardSpec: String? = null,
  val isPinned: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
)
