package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.loop.ToolTrace

data class ChatUiState(
  val conversationId: String? = null,
  val messages: List<Message> = emptyList(),
  val inputText: String = "",
  val isLoading: Boolean = false,
  val streamingText: String = "",
  val toolTrace: List<ToolTrace> = emptyList(),
  val error: String? = null,
  /** True when the last failed turn can be retried (we still have the last user message). */
  val canRetry: Boolean = false,
  val showToolTrace: Boolean = false,
  /** True when this conversation is a roadmap lesson — enables interactive cards. */
  val isTutorial: Boolean = false,
  /** Roadmap level id (the tutorial conversation title), used to mark completion. */
  val levelId: String? = null,
  /** When true, a sent message runs through the Plan-and-Execute orchestrator instead of the chat loop. */
  val planMode: Boolean = false,
  /** Bound mini-agent id; the coordinator routes through the supervisor, others scope the chat loop. */
  val agentId: String? = null,
  /** Display name shown in the chat header — the bound agent's name, else the conversation title. */
  val title: String? = null,
) {
  companion object {
    fun resetFor(id: String) = ChatUiState(conversationId = id)
  }
}
