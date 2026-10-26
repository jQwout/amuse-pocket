package com.aiopenpocket.agent.core.llm.usage

// @trace streaming-llm-chat-6595a1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Access to the accounting table. Only `RoomLlmUsageRecorder` writes; summaries read.
 *
 * Sums are computed by SQLite, not Kotlin: an active user accumulates thousands of rows a week, and
 * pulling them all into memory for one number is exactly the case where "let's just count it in code"
 * turns into loading the whole table for the settings screen.
 */
@Dao
interface LlmUsageDao {

  @Insert
  suspend fun insert(entity: LlmUsageEntity)

  /**
   * IFNULL around SUM is required: on an empty period SUM returns NULL, and Room would fail on it for a
   * non-nullable Long. For costUsd it is the opposite — NULL is kept, it means "nobody named a price".
   */
  @Query(
    """
    SELECT COUNT(*) AS calls,
           IFNULL(SUM(promptTokens), 0) AS promptTokens,
           IFNULL(SUM(completionTokens), 0) AS completionTokens,
           SUM(costUsd) AS costUsd,
           IFNULL(SUM(CASE WHEN estimated THEN 1 ELSE 0 END), 0) AS estimatedCalls
    FROM llm_usage
    WHERE at >= :since
    """,
  )
  suspend fun totalsSince(since: Long): LlmUsageTotals

  @Query(
    """
    SELECT model AS model,
           COUNT(*) AS calls,
           IFNULL(SUM(promptTokens), 0) AS promptTokens,
           IFNULL(SUM(completionTokens), 0) AS completionTokens,
           SUM(costUsd) AS costUsd
    FROM llm_usage
    WHERE at >= :since
    GROUP BY model
    ORDER BY promptTokens + completionTokens DESC
    """,
  )
  suspend fun totalsByModelSince(since: Long): List<LlmUsageByModel>

  /** Spend of calls OUTSIDE a turn (annotator, profile, background) — what the user neither sees nor asked for. */
  @Query(
    """
    SELECT COUNT(*) AS calls,
           IFNULL(SUM(promptTokens), 0) AS promptTokens,
           IFNULL(SUM(completionTokens), 0) AS completionTokens,
           SUM(costUsd) AS costUsd,
           IFNULL(SUM(CASE WHEN estimated THEN 1 ELSE 0 END), 0) AS estimatedCalls
    FROM llm_usage
    WHERE at >= :since AND conversationId IS NULL
    """,
  )
  suspend fun backgroundTotalsSince(since: Long): LlmUsageTotals
}
