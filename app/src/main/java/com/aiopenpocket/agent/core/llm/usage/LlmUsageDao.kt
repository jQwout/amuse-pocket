package com.aiopenpocket.agent.core.llm.usage

// @trace streaming-llm-chat-6595a1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Доступ к таблице учёта. Пишет только `RoomLlmUsageRecorder`, читают сводки.
 *
 * Суммы считает SQLite, а не Kotlin: строк на активном пользователе накапливаются тысячи в неделю,
 * и тянуть их все в память ради одного числа — ровно тот случай, когда «просто посчитаем в коде»
 * превращается в загрузку всей таблицы на экран настроек.
 */
@Dao
interface LlmUsageDao {

  @Insert
  suspend fun insert(entity: LlmUsageEntity)

  /**
   * IFNULL вокруг SUM обязателен: на пустом периоде SUM возвращает NULL, и Room уронил бы это на
   * не-nullable Long. У costUsd наоборот — NULL сохраняем, он значит «цену никто не называл».
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

  /** Расход вызовов ВНЕ хода (аннотатор, профиль, фон) — то, чего пользователь не видит и не просил. */
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
