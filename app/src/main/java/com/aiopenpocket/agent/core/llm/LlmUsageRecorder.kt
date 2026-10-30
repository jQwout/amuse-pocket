package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

/**
 * The spend of one FINISHED model call — what the provider has already counted and closed.
 *
 * A record per call, not per user turn, and that matters: one agent turn is up to
 * `ReactLoopRunner.MAX_ITERATIONS` calls in a row, each with its own context, plus calls the user does
 * not see (the conversation annotator, profile extraction, the planner, the step verifier, a background
 * heartbeat). Merging them into one row here would lose exactly what the accounting was started for:
 * seeing what a tool-heavy turn costs against a simple one, and how much the background eats.
 */
data class LlmCallUsage(
  val provider: LlmProvider,
  val model: String,
  val promptTokens: Int,
  val completionTokens: Int,
  /** Price in USD, if the provider named it. `null` — price it separately from the price list (see metaListPriceUsd). */
  val costUsd: Double?,
  /**
   * The tokens did not come from the provider but were estimated from the text length
   * (`LlmContextConfig.estimateTokens`).
   *
   * Stored rather than forgotten, because mixing measurement with estimate in one column gives a
   * summary nobody can say how far to trust. This flag shows which share of the spend we know exactly
   * and which we guessed.
   */
  val estimated: Boolean,
  /** The request carried tool schemas: tool calls are the main multiplier of the prompt size. */
  val hadTools: Boolean,
)

/**
 * Where spend accounting goes.
 *
 * An interface rather than writing to Room directly from the client: [OpenAiCompatClient] must not know
 * about the database (its tests run the transport through MockEngine and bring up no DB), and the
 * write itself is a storage question that will be solved differently on a server. The implementation
 * MUST NOT THROW: accounting cannot bring down a turn, the answer to the user matters more than a row
 * of statistics.
 */
interface LlmUsageRecorder {
  suspend fun record(usage: LlmCallUsage)
}

/** Writes nothing: tests and any paths without a DB. */
object NoOpLlmUsageRecorder : LlmUsageRecorder {
  override suspend fun record(usage: LlmCallUsage) = Unit
}
