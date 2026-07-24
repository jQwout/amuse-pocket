package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.loop.TurnStatus

data class ConversationsListState(
  val conversations: List<Conversation> = emptyList(),
  val isLoading: Boolean = false,
  val selectedTag: String? = null,
  /** Статусы фоновых ходов по диалогам: «варится» / «готово» / «ошибка» (нет в мапе = хода нет). */
  val turnStatuses: Map<String, TurnStatus> = emptyMap(),
) {
  /** Every tag in use across conversations, for the filter row. */
  val allTags: List<String>
    get() = conversations.flatMap { it.tags }.distinct().sorted()

  /** Conversations to show, narrowed to [selectedTag] when one is active. */
  val visibleConversations: List<Conversation>
    get() = selectedTag?.let { tag -> conversations.filter { tag in it.tags } } ?: conversations
}
