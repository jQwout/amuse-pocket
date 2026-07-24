package com.aiopenpocket.agent.core.srs

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SrsWordDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(word: SrsWordEntity)

  @Query("SELECT * FROM srs_words WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): SrsWordEntity?

  @Query("SELECT * FROM srs_words WHERE lang = :lang ORDER BY dueMs ASC")
  suspend fun getByLang(lang: String): List<SrsWordEntity>

  @Query("SELECT * FROM srs_words ORDER BY dueMs ASC")
  suspend fun getAll(): List<SrsWordEntity>

  @Query("DELETE FROM srs_words WHERE id = :id")
  suspend fun delete(id: String)
}
