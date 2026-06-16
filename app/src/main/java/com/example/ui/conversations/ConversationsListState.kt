package com.aiopenpocket.ui.conversations

import com.aiopenpocket.agent.core.memory.model.Conversation

data class ConversationsListState(
  val conversations: List<Conversation> = emptyList(),
  val isLoading: Boolean = false,
  val selectedTag: String? = null,
) {
  /** Every tag in use across conversations, for the filter row. */
  val allTags: List<String>
    get() = conversations.flatMap { it.tags }.distinct().sorted()

  /** Conversations to show, narrowed to [selectedTag] when one is active. */
  val visibleConversations: List<Conversation>
    get() = selectedTag?.let { tag -> conversations.filter { tag in it.tags } } ?: conversations
}
