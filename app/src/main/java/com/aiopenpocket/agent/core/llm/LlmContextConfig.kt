package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

/**
 * Budget for a single LLM request, with headroom reserved for the system prompt, tool schemas and the
 * model reply.
 */
object LlmContextConfig {
  /** Verbatim messages kept from the start of the dialog (topic / goals). */
  const val HEAD_MESSAGE_COUNT = 8

  /** Verbatim messages kept from the end of the dialog (recent context). */
  const val TAIL_MESSAGE_COUNT = 40

  /**
   * How many messages the start of the window moves at once in a long chat, both where the tail begins and
   * what the token budget drops. Meta's prompt cache matches the start of a request: a window that slid by
   * one message every turn changed the request right after the head, and the rest of the chat was billed in
   * full each time. Moving by 20 keeps it the same for about ten turns, at the price of up to 19 more
   * messages in the window.
   */
  // @trace the-prompt-cache-keeps-the-whole-chat-history-d25062
  const val WINDOW_SLIDE_STEP = 20

  const val RELEVANT_FACTS_LIMIT = 5
  const val EPISODES_LIMIT = 5

  /**
   * Ceiling of the facts section in the system prompt (characters). A small memory is shown in full
   * (see MemoryRepository.factsForPrompt), and without a ceiling one long fact — a diary entry, a
   * pasted text — would ride along in every turn.
   */
  const val PROMPT_FACTS_MAX_CHARS = 3_000

  /** Ceiling of one fact line in the prompt: the rest is available through search_memory. */
  const val PROMPT_FACT_LINE_MAX_CHARS = 300

  /** Rough token budget for verbatim dialog (system + tools are extra). */
  const val HISTORY_TOKEN_BUDGET = 48_000

  /**
   * How many of the latest messages are protected from budget trimming the longest — longer than the
   * start of the conversation: without them the model does not see what it is answering. The very last
   * one (the current question) is never dropped — see ContextWindowBuilder.trimVerbatimToTokenBudget.
   */
  const val PROTECTED_RECENT_MESSAGES = 6

  /** Middle section smaller than this stays verbatim (merged into head/tail). */
  const val MIN_MIDDLE_MESSAGES_FOR_SUMMARY = 4

  /** Target size for the summary stored on [Conversation.summary]. */
  const val SUMMARY_MAX_CHARS = 4_000

  /** Background annotation (title/summary/tags) needs at least this many messages to be meaningful. */
  const val MIN_MESSAGES_FOR_ANNOTATION = 2

  /** Most recent messages fed to the annotator when deriving title/summary/tags. */
  const val ANNOTATION_MESSAGE_LIMIT = 40

  /**
   * Rate gate of the background annotator: after the initial annotation the title/summary/tags are
   * refreshed once per this many text messages (not after every turn — that would be a second LLM call
   * per turn).
   */
  const val ANNOTATE_EVERY_MESSAGES = 4

  /** Upper bound on auto-generated tags per conversation. */
  const val MAX_TAGS = 5

  fun estimateTokens(text: String): Int = estimateTokens(text.length)

  /**
   * The same estimate when only the length is at hand. Spend accounting needs it: there is no string
   * to count there — the request body has already been sent, and the answer arrived in deltas, so only
   * the sum of their lengths is known. One formula for both cases, otherwise the context window
   * estimate and the spend estimate would drift apart silently.
   */
  fun estimateTokens(chars: Int): Int = maxOf(1, chars / 3)
}
