package com.aiopenpocket.agent.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A single tag attached to a conversation. The knowledge base is "graph-shaped without graphs":
 * conversations relate to each other purely by shared tags, so there is no edge table — overlap in
 * this join table is the association.
 */
@Entity(
  tableName = "conversation_tags",
  primaryKeys = ["conversationId", "tag"],
  foreignKeys = [
    ForeignKey(
      entity = ConversationEntity::class,
      parentColumns = ["id"],
      childColumns = ["conversationId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("tag"), Index("conversationId")],
)
data class ConversationTagEntity(
  val conversationId: String,
  val tag: String,
)
