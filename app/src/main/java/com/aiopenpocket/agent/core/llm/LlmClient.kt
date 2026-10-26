package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.coroutines.flow.Flow

interface LlmClient {
  /**
   * The provider is a REQUIRED parameter, with no default value.
   *
   * It used to be `= LlmProvider.NIM`, and two calls simply did not pass it: the conversation annotator
   * (it runs after every message, and its body is a verbatim piece of the conversation) and profile
   * extraction from the "about me" text. Both silently went to NVIDIA even after the user had
   * deliberately switched to another vendor, and did not fail: the NIM key was baked into the build. A
   * default turns "forgot to pass the provider" into quietly sending data to the wrong recipient;
   * without it that is a compile error.
   */
  fun streamChat(
    messages: List<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider,
  ): Flow<StreamEvent>
}
