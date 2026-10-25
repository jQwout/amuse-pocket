package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.serialization.json.JsonElement

sealed class StreamEvent {
  /**
   * Кусок ответа. [reasoning] = true — это не ответ, а размышление модели, отданное вместо ответа,
   * когда content так и не пришёл (см. `consumeOpenAiSse`): чату его показать лучше, чем пустоту, а
   * вызывающим, ждущим JSON (аннотатор, проверка шага плана, разбор профиля), брать его за ответ
   * нельзя — в размышлении бывают черновики JSON, которые разберутся как настоящий результат.
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
