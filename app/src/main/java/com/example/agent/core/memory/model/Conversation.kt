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
  /** Закреплён пользователем в избранное — всплывает в начало списка диалогов. */
  val isFavorite: Boolean = false,
  /** Курсор урока: индекс следующего шага для показа (0 = урок ещё не начат). */
  val lessonStepIndex: Int = 0,
  /** Урок полностью пройден. */
  val lessonFinished: Boolean = false,
)
