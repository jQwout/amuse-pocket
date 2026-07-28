package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [UserAgentEntity] rows. [UserAgentRepository] is the only caller — it owns the
 * status/configVersion transitions (publish/archive) and the [AgentDefinition] mapping; this DAO
 * only moves rows in and out of SQLite.
 */
@Dao
interface UserAgentDao {
  @Query("SELECT * FROM user_agents WHERE id = :id")
  suspend fun get(id: String): UserAgentEntity?

  @Query("SELECT * FROM user_agents WHERE status = 'PUBLISHED' ORDER BY updatedAt DESC")
  fun observePublished(): Flow<List<UserAgentEntity>>

  /** Backs [UserAgentRepository.observeDrafts] — the roster's «Черновики» section. ARCHIVED stays out of both listings. */
  @Query("SELECT * FROM user_agents WHERE status = 'DRAFT' ORDER BY updatedAt DESC")
  fun observeDrafts(): Flow<List<UserAgentEntity>>

  @Upsert
  suspend fun upsert(entity: UserAgentEntity)

  @Query("UPDATE user_agents SET status = :status, updatedAt = :now WHERE id = :id")
  suspend fun updateStatus(id: String, status: String, now: Long)
}
