package com.aiopenpocket.agent.core.memory.dao

// @trace multi-chat-conversations-b3c93b

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun getAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ConversationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(conversation: ConversationEntity)

  @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

  @Query("UPDATE conversations SET summary = :summary, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateSummary(id: String, summary: String, updatedAt: Long = System.currentTimeMillis())

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun delete(id: String)
}
