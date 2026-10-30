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
  // A composite index made exactly for the hot chat window query (WHERE conversationId=? ORDER BY
  // createdAt DESC LIMIT ?): a seek by prefix + a backward scan, O(limit) instead of sorting all the
  // conversation's rows on every re-emit. The leading column also covers the FK index requirement.
  indices = [Index("conversationId", "createdAt")],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val role: String, // "user", "assistant", "system", "tool"
  val content: String,
  val toolCallsJson: String? = null,
  /** Driven UI card spec ({"template":...,"params":...}) rendered as markdown in chat. */
  val cardSpec: String? = null,
  /**
   * The user's state inside an interactive card (today: the chosen options of a lesson quiz, see
   * [com.aiopenpocket.ui.roadmap.QuizAnswers]). Kept apart from [cardSpec] on purpose: the spec is the
   * authored content and is re-parsed for the card, while this is what the person did with it — it must
   * survive leaving the lesson, which the composition's own saved state does not.
   */
  val cardState: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
)
