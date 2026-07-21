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

  @Query("SELECT * FROM facts WHERE `key` LIKE '%' || :query || '%' ESCAPE '\\' OR value LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
  suspend fun search(query: String): List<FactEntity>

  @Query("SELECT * FROM facts WHERE category = :category ORDER BY updatedAt DESC")
  suspend fun getByCategory(category: String): List<FactEntity>

  @Query("SELECT * FROM facts WHERE `key` LIKE :prefix || '%' ESCAPE '\\' ORDER BY `key` ASC, updatedAt DESC")
  suspend fun getByKeyPrefix(prefix: String): List<FactEntity>

  /**
   * [query]-matched facts confined to [namespace] (an agent's `agent:{id}:` key prefix) — the
   * per-agent counterpart of [search]. The namespace constraint is ANDed into the WHERE clause
   * itself (not a post-filter over [search]'s results) so a caller that takes only the top N rows
   * (e.g. `PrePromptAssembler`'s relevant-facts limit) never has another agent's/global facts
   * crowd out this agent's own matches before they are even considered — see `MemoryRepository`.
   */
  @Query(
    "SELECT * FROM facts WHERE `key` LIKE :namespace || '%' ESCAPE '\\' " +
      "AND (`key` LIKE '%' || :query || '%' ESCAPE '\\' OR value LIKE '%' || :query || '%' ESCAPE '\\') " +
      "ORDER BY updatedAt DESC",
  )
  suspend fun searchInNamespace(namespace: String, query: String): List<FactEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(fact: FactEntity)

  @Query("DELETE FROM facts WHERE id = :id")
  suspend fun delete(id: String)
}
