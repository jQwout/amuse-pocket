package com.aiopenpocket.agent.core.memory.dao

// @trace agent-memory-05e545
// @trace user-agent-builder-848153

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

  /**
   * Вся память фактов, свежие первыми — источник для пословного поиска
   * [com.aiopenpocket.agent.core.memory.repository.MemoryRepository.searchFacts].
   *
   * Сопоставление сознательно НЕ здесь: `LIKE` в SQLite сворачивает регистр только для ASCII, так
   * что «казань» не нашло бы «Казань». Отбор идёт в Kotlin — см. kdoc `rankByQuery`.
   */
  @Query("SELECT * FROM facts ORDER BY updatedAt DESC")
  suspend fun getAll(): List<FactEntity>

  @Query("SELECT * FROM facts WHERE category = :category ORDER BY updatedAt DESC")
  suspend fun getByCategory(category: String): List<FactEntity>

  @Query("SELECT * FROM facts WHERE `key` LIKE :prefix || '%' ESCAPE '\\' ORDER BY `key` ASC, updatedAt DESC")
  suspend fun getByKeyPrefix(prefix: String): List<FactEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(fact: FactEntity)

  @Query("DELETE FROM facts WHERE id = :id")
  suspend fun delete(id: String)
}
