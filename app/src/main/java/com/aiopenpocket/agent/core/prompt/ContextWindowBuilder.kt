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
   * The same prompt window, but from ready-made parts (head/tail/count from limited SQL queries, see
   * ConversationRepository.getPromptWindowParts) — without loading the whole history into memory.
   * The parts are considered already filtered of empty cards. When the whole conversation fits into
   * head+tail, the entire history comes in [head], and [tail] is empty.
   */
  fun buildFromParts(
    head: List<Message>,
    tail: List<Message>,
    totalPromptable: Int,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    tailCount: Int = LlmContextConfig.TAIL_MESSAGE_COUNT,
    budgetTokens: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
    /** Drop the old part of the tail in whole runs of this many messages, see [trimVerbatimToTokenBudget]. */
    dropStep: Int = 1,
  ): Window {
    if (totalPromptable == 0) {
      return Window(emptyList(), hasMiddleGap = false, middleMessageCount = 0)
    }
    val verbatim = (head + tail).map { it.toPromptMessage() }
    // The token budget applies ALWAYS, not only when the conversation is longer than head+tail. A short
    // conversation (≤ 48 messages) used to go out whole without a check: a couple of pasted walls of
    // text — and the request exceeded the model's window, the provider answered 400, and the user saw
    // "no network".
    val kept = trimVerbatimToTokenBudget(verbatim, budgetTokens, headCount, dropStep = dropStep)
    val dropped = verbatim.size - kept.size
    // From the parts' real sizes: the tail may be longer than tailCount (getPromptWindowParts' tailStep).
    val skipped = (totalPromptable - head.size - tail.size).coerceAtLeast(0)
    // Messages dropped by the budget are also a "middle" the model must know about: otherwise it thinks it
    // sees the whole conversation.
    val middleCount = skipped + dropped
    return Window(
      verbatim = kept,
      hasMiddleGap = dropped > 0 || skipped >= LlmContextConfig.MIN_MIDDLE_MESSAGES_FOR_SUMMARY,
      middleMessageCount = middleCount,
    )
  }

  fun formatMessagesForSummary(messages: List<Message>): String =
    messages.joinToString("\n\n") { msg ->
      "${msg.role}: ${msg.content.trim()}"
    }

  private fun Message.toPromptMessage() =
    PrePromptAssembler.PromptMessage(role, contentForModel(), toolCallsJson, imagePath)

  /**
   * A reflection answer is stored whole — draft, critique, result: the chat renders all three phases.
   * The model needs only the result in later turns; the draft with the critique would triple the
   * message in the context and pass off a rejected variant as something that was said.
   */
  private fun Message.contentForModel(): String =
    if (role == "assistant" && content.contains(REFLECTION_FINAL_HEADING)) {
      content.substringAfterLast(REFLECTION_FINAL_HEADING).trim()
    } else {
      content
    }

  /**
   * Trims the window to [budgetTokens], dropping messages in "the less needed, the earlier" order:
   *
   * 1. the old part of the tail — between the start of the conversation and the last [protectedRecent]
   *    messages, from old to new;
   * 2. the start of the conversation (the first [headCount] messages) — from its end, so the very first
   *    message (the topic) is the last of the head to go;
   * 3. the last [protectedRecent] messages, from old to new;
   * 4. the very last message — NEVER: it is the user's current message, and without it the turn loses
   *    its meaning. If it alone is over the budget, it goes as is (better the provider refuses than the
   *    model answers nothing).
   *
   * The old algorithm, having trimmed the tail down to "head + one message", dropped exactly that one
   * next — the current question — while keeping eight greeting messages.
   *
   * Step 1 drops whole runs of [dropStep] messages: the window then starts on the same message for several
   * turns, which is what keeps it in Meta's prompt cache (see [LlmContextConfig.WINDOW_SLIDE_STEP]).
   */
  internal fun trimVerbatimToTokenBudget(
    verbatim: List<PrePromptAssembler.PromptMessage>,
    budgetTokens: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
    headCount: Int = LlmContextConfig.HEAD_MESSAGE_COUNT,
    protectedRecent: Int = LlmContextConfig.PROTECTED_RECENT_MESSAGES,
    dropStep: Int = 1,
  ): List<PrePromptAssembler.PromptMessage> {
    if (verbatim.size <= 1) return verbatim

    fun tokensOf(msg: PrePromptAssembler.PromptMessage): Int =
      LlmContextConfig.estimateTokens(msg.content) +
        (msg.toolCallsJson?.let { LlmContextConfig.estimateTokens(it) } ?: 0)

    var total = verbatim.sumOf { tokensOf(it) }
    if (total <= budgetTokens) return verbatim

    val last = verbatim.lastIndex
    val recentStart = maxOf(0, verbatim.size - protectedRecent)
    val headEnd = minOf(headCount, recentStart) // the head — only what is not part of the protected tail
    val dropOrder = (headEnd until recentStart) + // 1. the old part of the tail
      (headEnd - 1 downTo 0) + // 2. the start of the conversation, from its end
      (recentStart until last) // 3. the recent ones, except the very last
    val oldTail = headEnd until recentStart
    val dropped = HashSet<Int>()
    var droppedFromOldTail = 0
    for (index in dropOrder) {
      // @trace the-prompt-cache-keeps-the-whole-chat-history-d25062
      val midRun = index in oldTail && droppedFromOldTail % dropStep.coerceAtLeast(1) != 0
      if (total <= budgetTokens && !midRun) break
      total -= tokensOf(verbatim[index])
      dropped += index
      if (index in oldTail) droppedFromOldTail++
    }
    return verbatim.filterIndexed { index, _ -> index !in dropped }
  }
}

/**
 * Heading of the final part of a saved reflection answer (draft + critique + result). It lives here, not
 * in ReflectionOrchestrator: the history builder (the prompt layer) must not depend on orchestrators
 * (the loop layer), and the orchestrator references this constant.
 */
const val REFLECTION_FINAL_HEADING = "\n\n---\n\n✨ **Итог**\n"
