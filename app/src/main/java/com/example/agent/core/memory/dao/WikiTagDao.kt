package com.aiopenpocket.agent.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.WikiPageEntity
import com.aiopenpocket.agent.core.memory.entity.WikiPageTagEntity

@Dao
interface WikiTagDao {
  // IGNORE: re-tagging with an existing (pageId, tag) pair is a no-op, not an error.
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(tag: WikiPageTagEntity)

  @Query("DELETE FROM wiki_page_tags WHERE pageId = :pageId")
  suspend fun deleteForPage(pageId: String)

  @Query("DELETE FROM wiki_page_tags WHERE pageId = :pageId AND tag = :tag")
  suspend fun delete(pageId: String, tag: String)

  @Query("SELECT tag FROM wiki_page_tags WHERE pageId = :pageId ORDER BY tag")
  suspend fun getTagsForPage(pageId: String): List<String>

  /** Pages matching ANY of [tags], ranked by how many of the tags they match, then recency. */
  @Query(
    "SELECT p.* FROM wiki_pages p " +
      "INNER JOIN wiki_page_tags t ON t.pageId = p.id " +
      "WHERE t.tag IN (:tags) " +
      "GROUP BY p.id " +
      "ORDER BY COUNT(t.tag) DESC, p.updatedAt DESC " +
      "LIMIT :limit",
  )
  suspend fun getPagesByTags(tags: List<String>, limit: Int): List<WikiPageEntity>

  @Query("SELECT DISTINCT tag FROM wiki_page_tags ORDER BY tag")
  suspend fun getAllTags(): List<String>
}
