package com.aiopenpocket.agent.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.FactEntity

@Dao
interface FactDao {
  @Query("SELECT * FROM facts WHERE `key` = :key LIMIT 1")
  suspend fun getByKey(key: String): FactEntity?

  @Query("DELETE FROM facts WHERE `key` = :key")
  suspend fun deleteByKey(key: String)

  @Query("SELECT * FROM facts WHERE `key` LIKE '%' || :query || '%' ESCAPE '\\' OR value LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
  suspend fun search(query: String): List<FactEntity>

  @Query("SELECT * FROM facts WHERE category = :category ORDER BY updatedAt DESC")
  suspend fun getByCategory(category: String): List<FactEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(fact: FactEntity)

  @Query("DELETE FROM facts WHERE id = :id")
  suspend fun delete(id: String)
}
