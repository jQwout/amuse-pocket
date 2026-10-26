package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.serialization.json.JsonElement

sealed class StreamEvent {
  /**
   * A piece of the answer. [reasoning] = true — this is not the answer but the model's thinking,
   * returned instead of an answer when content never arrived (see `consumeOpenAiSse`): showing it in
   * the chat is better than emptiness, but callers waiting for JSON (the annotator, the plan step check,
   * profile parsing) must not take it as the answer — thinking sometimes contains JSON drafts that would
   * parse as a real result.
   */
  data class TextDelta(val content: String, val reasoning: Boolean = false) : StreamEvent()
  data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>,
  ) : StreamEvent()
  data class Finish(val reason: String) : StreamEvent()
  data class Error(val message: String, val code: String? = null) : StreamEvent()
}
