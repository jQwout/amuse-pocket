package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.coroutines.flow.Flow

interface LlmClient {
  fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<StreamEvent>
}
