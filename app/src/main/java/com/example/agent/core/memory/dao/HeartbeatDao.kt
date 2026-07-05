package com.aiopenpocket.agent.core.memory.dao

// @trace heartbeat-napominalki-agenta-5f0442

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(heartbeat: HeartbeatEntity)

  @Query("SELECT * FROM heartbeats WHERE id = :id")
  suspend fun getById(id: String): HeartbeatEntity?

  /** Живая карточка в чате подписывается на эту строку. */
  @Query("SELECT * FROM heartbeats WHERE id = :id")
  fun observe(id: String): Flow<HeartbeatEntity?>

  @Query("SELECT * FROM heartbeats WHERE status = 'scheduled' ORDER BY createdAt DESC")
  suspend fun listActive(): List<HeartbeatEntity>

  @Query("UPDATE heartbeats SET status = :status WHERE id = :id")
  suspend fun setStatus(id: String, status: String)

  @Query("UPDATE heartbeats SET status = :status, lastRunAt = :lastRunAt, lastResult = :lastResult WHERE id = :id")
  suspend fun recordRun(id: String, status: String, lastRunAt: Long, lastResult: String?)
}
