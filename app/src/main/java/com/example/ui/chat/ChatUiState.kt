package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.loop.ToolTrace

data class ResponseStats(
  val responseTimeMs: Long,
  val completionTokens: Int,
  val contextTokensUsed: Int,
  val contextBudget: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
)

data class ChatUiState(
  val conversationId: String? = null,
  val messages: List<Message> = emptyList(),
  val inputText: String = "",
  val isLoading: Boolean = false,
  val streamingText: String = "",
  val toolTrace: List<ToolTrace> = emptyList(),
  val error: String? = null,
  val showToolTrace: Boolean = false,
  val lastResponseStats: ResponseStats? = null,
  /** True when this conversation is a roadmap lesson — enables interactive cards. */
  val isTutorial: Boolean = false,
  /** Roadmap level id (the tutorial conversation title), used to mark completion. */
  val levelId: String? = null,
)
