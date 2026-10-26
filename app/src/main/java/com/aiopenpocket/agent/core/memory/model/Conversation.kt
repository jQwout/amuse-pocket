package com.aiopenpocket.agent.core.memory.model

// @trace multi-chat-conversations-b3c93b

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
  /** Pinned by the user as a favorite — rises to the top of the conversation list. */
  val isFavorite: Boolean = false,
  /** The lesson cursor: the index of the next step to show (0 = the lesson has not started). */
  val lessonStepIndex: Int = 0,
  /** The lesson is fully completed. */
  val lessonFinished: Boolean = false,
)
