package com.aiopenpocket.agent.core.memory.dao

// @trace agent-memory-05e545

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.EpisodeEntity

@Dao
interface EpisodeDao {
  @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT :limit")
  suspend fun getRecent(limit: Int): List<EpisodeEntity>

  @Query(
    "SELECT * FROM episodes WHERE conversationId = :conversationId " +
      "ORDER BY timestamp DESC LIMIT :limit",
  )
  suspend fun getRecentForConversation(conversationId: String, limit: Int): List<EpisodeEntity>

  /** The window [since, until) of one conversation — the source of `recall_episodes` (see MemoryRepository). */
  @Query(
    "SELECT * FROM episodes WHERE conversationId = :conversationId " +
      "AND timestamp >= :since AND timestamp < :until ORDER BY timestamp DESC LIMIT :limit",
  )
  suspend fun getForConversationBetween(
    conversationId: String,
    since: Long,
    until: Long,
    limit: Int,
  ): List<EpisodeEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(episode: EpisodeEntity)

  @Query("DELETE FROM episodes WHERE id = :id")
  suspend fun delete(id: String)
}
