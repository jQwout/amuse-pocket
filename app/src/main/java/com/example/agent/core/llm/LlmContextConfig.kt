package com.aiopenpocket.agent.core.llm

/**
 * Budget for a single LLM request. Llama 3.1 supports ~128K tokens; we reserve headroom
 * for system prompt, tool schemas, and the model reply.
 */
object LlmContextConfig {
  /** Verbatim messages kept from the start of the dialog (topic / goals). */
  const val HEAD_MESSAGE_COUNT = 8

  /** Verbatim messages kept from the end of the dialog (recent context). */
  const val TAIL_MESSAGE_COUNT = 40

  /** Max messages loaded when the dialog fits in head + tail without a gap. */
  const val FULL_HISTORY_LIMIT = 500

  const val RELEVANT_FACTS_LIMIT = 5
  const val EPISODES_LIMIT = 5

  /** Rough token budget for verbatim dialog (system + tools are extra). */
  const val HISTORY_TOKEN_BUDGET = 48_000

  /** Middle section smaller than this stays verbatim (merged into head/tail). */
  const val MIN_MIDDLE_MESSAGES_FOR_SUMMARY = 4

  /** Target size for the rolling middle summary stored on [Conversation.summary]. */
  const val SUMMARY_MAX_CHARS = 4_000

  fun estimateTokens(text: String): Int = maxOf(1, text.length / 3)
}
