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

  /** One row by its id — the Profile screen's "forget this" removes exactly the fact the person tapped, not every row of a key. */
  @Query("DELETE FROM facts WHERE id = :id")
  suspend fun deleteById(id: String)

  /**
   * The whole fact memory, freshest first — the source for the word-by-word search
   * [com.aiopenpocket.agent.core.memory.repository.MemoryRepository.searchFacts].
   *
   * Matching is deliberately NOT here: SQLite `LIKE` folds case only for ASCII, so "казань" would not
   * find "Казань". Selection happens in Kotlin — see the kdoc of `rankByQuery`.
   */
  @Query("SELECT * FROM facts ORDER BY updatedAt DESC")
  suspend fun getAll(): List<FactEntity>

  @Query("SELECT * FROM facts WHERE category = :category ORDER BY updatedAt DESC")
  suspend fun getByCategory(category: String): List<FactEntity>

  @Query("SELECT * FROM facts WHERE `key` LIKE :prefix || '%' ESCAPE '\\' ORDER BY `key` ASC, updatedAt DESC")
  suspend fun getByKeyPrefix(prefix: String): List<FactEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(fact: FactEntity)
}
