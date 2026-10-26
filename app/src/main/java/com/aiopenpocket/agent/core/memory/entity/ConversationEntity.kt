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
  /** Pinned by the user as a favorite — rises to the top of the conversation list. */
  val isFavorite: Boolean = false,
  /** The lesson cursor: the index of the next step to show (0 = the lesson has not started). Explicit state rather than
   *  something derived from the number of assistant messages — otherwise a free question to the LLM during a lesson would move the step. */
  val lessonStepIndex: Int = 0,
  /** The lesson is fully completed (the completion card was shown). */
  val lessonFinished: Boolean = false,
)
