package com.aiopenpocket.agent.core.prompt

import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message

/**
 * Builds a head + [summary of middle] + tail window for long dialogs.
 */
object ContextWindowBuilder {

  data class Window(
    val verbatim: List<PrePromptAssembler.PromptMessage>,
    val hasMiddleGap: Boolean,
    val middleMessageCount: Int,
  )

  fun build(
    rawMessages: List<Message>,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    tailCount: Int = LlmContextConfig.TAIL_MESSAGE_COUNT,
  ): Window {
    // Card-only messages carry no text and must not be sent to the LLM as empty
    // assistant turns (some providers reject blank content).
    val allMessages = rawMessages.filter {
      it.content.isNotBlank() || it.toolCallsJson != null
    }
    if (allMessages.isEmpty()) {
      return Window(emptyList(), hasMiddleGap = false, middleMessageCount = 0)
    }

    val total = allMessages.size
    if (total <= headCount + tailCount) {
      return Window(
        verbatim = allMessages.map { it.toPromptMessage() },
        hasMiddleGap = false,
        middleMessageCount = 0,
      )
    }

    val head = allMessages.take(headCount)
    val tail = allMessages.takeLast(tailCount)
    val middleCount = total - headCount - tailCount

    val verbatim = (head + tail).map { it.toPromptMessage() }
    return Window(
      verbatim = trimVerbatimToTokenBudget(verbatim),
      hasMiddleGap = middleCount >= LlmContextConfig.MIN_MIDDLE_MESSAGES_FOR_SUMMARY,
      middleMessageCount = middleCount,
    )
  }

  fun formatMessagesForSummary(messages: List<Message>): String =
    messages.joinToString("\n\n") { msg ->
      "${msg.role}: ${msg.content.trim()}"
    }

  private fun Message.toPromptMessage() =
    PrePromptAssembler.PromptMessage(role, content, toolCallsJson)

  /**
   * When head + tail exceed the token budget, drop oldest messages from the tail portion first,
   * then from the head, keeping at least one message.
   */
  internal fun trimVerbatimToTokenBudget(
    verbatim: List<PrePromptAssembler.PromptMessage>,
    budgetTokens: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
  ): List<PrePromptAssembler.PromptMessage> {
    if (verbatim.isEmpty()) return verbatim

    fun tokensOf(msg: PrePromptAssembler.PromptMessage): Int =
      LlmContextConfig.estimateTokens(msg.content) +
        (msg.toolCallsJson?.let { LlmContextConfig.estimateTokens(it) } ?: 0)

    val indices = verbatim.indices.toMutableList()
    var total = indices.sumOf { tokensOf(verbatim[it]) }

    while (total > budgetTokens && indices.size > 1) {
      val headSize = minOf(LlmContextConfig.HEAD_MESSAGE_COUNT, indices.size)
      val tailStart = maxOf(headSize, indices.size - LlmContextConfig.TAIL_MESSAGE_COUNT)
      val dropIndex = when {
        indices.size > tailStart -> indices[tailStart]
        headSize > 0 -> indices[headSize - 1]
        else -> indices.first()
      }
      total -= tokensOf(verbatim[dropIndex])
      indices.remove(dropIndex)
    }

    return indices.map { verbatim[it] }
  }
}
