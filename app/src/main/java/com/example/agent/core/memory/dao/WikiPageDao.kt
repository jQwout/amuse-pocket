package com.aiopenpocket.agent.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.WikiPageEntity

@Dao
interface WikiPageDao {
  // ABORT (not REPLACE): REPLACE = DELETE + INSERT, which would cascade-wipe this page's tags
  // and links. New pages use this; existing pages are mutated via [update].
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(page: WikiPageEntity)

  @Query("UPDATE wiki_pages SET title = :title, content = :content, updatedAt = :updatedAt WHERE id = :id")
  suspend fun update(id: String, title: String, content: String, updatedAt: Long)

  @Query("SELECT * FROM wiki_pages WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): WikiPageEntity?

  @Query("SELECT * FROM wiki_pages WHERE slug = :slug LIMIT 1")
  suspend fun getBySlug(slug: String): WikiPageEntity?

  @Query("SELECT * FROM wiki_pages WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<String>): List<WikiPageEntity>

  @Query("SELECT * FROM wiki_pages ORDER BY updatedAt DESC LIMIT :limit")
  suspend fun getRecent(limit: Int): List<WikiPageEntity>

  @Query(
    "SELECT * FROM wiki_pages WHERE title LIKE '%' || :query || '%' ESCAPE '\\' " +
      "OR content LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC LIMIT :limit",
  )
  suspend fun search(query: String, limit: Int): List<WikiPageEntity>

  @Query("DELETE FROM wiki_pages WHERE id = :id")
  suspend fun delete(id: String)
}
