package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

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

  /** Target size for the summary stored on [Conversation.summary]. */
  const val SUMMARY_MAX_CHARS = 4_000

  /** Background annotation (title/summary/tags) needs at least this many messages to be meaningful. */
  const val MIN_MESSAGES_FOR_ANNOTATION = 2

  /** Most recent messages fed to the annotator when deriving title/summary/tags. */
  const val ANNOTATION_MESSAGE_LIMIT = 40

  /**
   * Гейт частоты фонового аннотатора: после первичной аннотации обновляем заголовок/резюме/теги
   * раз в столько текстовых реплик (а не после каждого хода — это второй LLM-вызов на ход).
   */
  const val ANNOTATE_EVERY_MESSAGES = 4

  /** Upper bound on auto-generated tags per conversation. */
  const val MAX_TAGS = 5

  fun estimateTokens(text: String): Int = estimateTokens(text.length)

  /**
   * Та же оценка, когда на руках только длина. Нужна учёту расхода: там считать нечего по строке —
   * тело запроса уже отправлено, а ответ приходил дельтами, и известна лишь сумма их длин. Одна
   * формула на оба случая, иначе оценка окна контекста и оценка расхода разъедутся молча.
   */
  fun estimateTokens(chars: Int): Int = maxOf(1, chars / 3)
}
