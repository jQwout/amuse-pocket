package com.aiopenpocket.agent.core.storage

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Room access for [AgentStorageEntity]. [AgentStorageRepository] is the only caller - mirrors
 * [com.aiopenpocket.agent.core.hook.AgentHookDao]: this Dao only moves rows in and out of SQLite,
 * leaving the agentId-from-turn-context enforcement to `StorageTool` and any higher-level policy
 * (value/label size caps, key-prefix filtering) to the repository/tool layer.
 */
@Dao
interface AgentStorageDao {
  @Upsert
  suspend fun upsert(entity: AgentStorageEntity)

  @Query("SELECT * FROM agent_storage WHERE agentId = :agentId AND collection = :collection AND key = :key")
  suspend fun get(agentId: String, collection: String, key: String): AgentStorageEntity?

  /** Every entry in ([agentId], [collection]) - [AgentStorageRepository.list] does the optional key-prefix narrowing in Kotlin, not here. */
  @Query("SELECT * FROM agent_storage WHERE agentId = :agentId AND collection = :collection ORDER BY key ASC")
  suspend fun listByCollection(agentId: String, collection: String): List<AgentStorageEntity>

  @Query("DELETE FROM agent_storage WHERE agentId = :agentId AND collection = :collection AND key = :key")
  suspend fun delete(agentId: String, collection: String, key: String)

  /** Wipes every entry owned by [agentId], across every collection - see [AgentStorageRepository.deleteByAgent]. */
  @Query("DELETE FROM agent_storage WHERE agentId = :agentId")
  suspend fun deleteByAgent(agentId: String)
}
