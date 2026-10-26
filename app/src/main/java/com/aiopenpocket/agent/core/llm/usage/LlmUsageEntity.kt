package com.aiopenpocket.agent.core.llm.usage

// @trace streaming-llm-chat-6595a1

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One accounting row — one finished model call (see `LlmCallUsage`).
 *
 * The table is only appended to and read by summaries; rows are never updated or deleted one by one.
 * Hence a surrogate auto key rather than a composite one: the event "a call happened" has no natural
 * key, two identical calls in a row are two different facts, and they must not be merged.
 *
 * [conversationId] and [agentId] are filled in WHEN POSSIBLE, from the turn context. `null` is not a
 * gap in the data but a meaningful answer: that is what calls outside a turn look like (the
 * conversation annotator, profile extraction), and the share of such rows is exactly what answers
 * "how much does the background eat".
 */
@Entity(
  tableName = "llm_usage",
  indices = [Index("at"), Index("conversationId")],
)
data class LlmUsageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** The moment the call finished, epoch millis. */
  val at: Long,
  val provider: String,
  val model: String,
  val conversationId: String?,
  val agentId: String?,
  val promptTokens: Int,
  val completionTokens: Int,
  val costUsd: Double?,
  val estimated: Boolean,
  val hadTools: Boolean,
)

/** Totals over all calls of a period. A separate type — Room cannot return a tuple. */
data class LlmUsageTotals(
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  /** `null` when no call of the period named a price (the provider returned only tokens). */
  val costUsd: Double?,
  /** How many rows of the period are estimated rather than measured — a measure of trust in the summary. */
  val estimatedCalls: Int,
)

/** The same totals, broken down by model: where exactly the money burns. */
data class LlmUsageByModel(
  val model: String,
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  val costUsd: Double?,
)
