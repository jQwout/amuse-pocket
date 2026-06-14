package com.aiopenpocket.ui.conversations

import com.aiopenpocket.agent.core.memory.model.Conversation

data class ConversationsListState(
  val conversations: List<Conversation> = emptyList(),
  val isLoading: Boolean = false,
)
