package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.voice.input.VoiceInputState

data class ChatUiState(
  val conversationId: String? = null,
  val messages: List<Message> = emptyList(),
  val inputText: String = "",
  val isLoading: Boolean = false,
  val streamingText: String = "",
  val toolTrace: List<ToolTrace> = emptyList(),
  val error: String? = null,
  val showToolTrace: Boolean = false,
  /** True when this conversation is a roadmap lesson — enables interactive cards. */
  val isTutorial: Boolean = false,
  /** Roadmap level id (the tutorial conversation title), used to mark completion. */
  val levelId: String? = null,
  val voiceState: VoiceInputState = VoiceInputState.Idle,
  val voiceError: String? = null,
)
