package com.aiopenpocket.agent.core.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
  fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider = LlmProvider.AUTO,
  ): Flow<StreamEvent>
}
