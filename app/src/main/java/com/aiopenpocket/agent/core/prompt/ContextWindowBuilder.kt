package com.aiopenpocket.agent.core.prompt

// @trace streaming-llm-chat-6595a1

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
    val total = allMessages.size
    return if (total <= headCount + tailCount) {
      buildFromParts(allMessages, emptyList(), total, headCount, tailCount)
    } else {
      buildFromParts(
        head = allMessages.take(headCount),
        tail = allMessages.takeLast(tailCount),
        totalPromptable = total,
        headCount = headCount,
        tailCount = tailCount,
      )
    }
  }

  /**
   * То же промпт-окно, но из уже готовых частей (head/tail/счётчик из лимитированных SQL-выборок,
   * см. ConversationRepository.getPromptWindowParts) — без загрузки всей истории в память.
   * Части считаются уже отфильтрованными от пустых карточек. Когда диалог целиком помещается в
   * head+tail, вся история приходит в [head], а [tail] пуст.
   */
  fun buildFromParts(
    head: List<Message>,
    tail: List<Message>,
    totalPromptable: Int,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    tailCount: Int = LlmContextConfig.TAIL_MESSAGE_COUNT,
  ): Window {
    if (totalPromptable == 0) {
      return Window(emptyList(), hasMiddleGap = false, middleMessageCount = 0)
    }
    if (totalPromptable <= headCount + tailCount) {
      return Window(
        verbatim = (head + tail).map { it.toPromptMessage() },
        hasMiddleGap = false,
        middleMessageCount = 0,
      )
    }

    val middleCount = totalPromptable - headCount - tailCount
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
