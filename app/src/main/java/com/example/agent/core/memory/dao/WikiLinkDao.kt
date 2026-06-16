package com.aiopenpocket.agent.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.WikiLinkEntity

@Dao
interface WikiLinkDao {
  // IGNORE: re-linking the same (from, to, relation) keeps the original createdAt.
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(link: WikiLinkEntity)

  @Query("DELETE FROM wiki_links WHERE fromPageId = :from AND toPageId = :to AND relation = :relation")
  suspend fun delete(from: String, to: String, relation: String)

  @Query("SELECT * FROM wiki_links WHERE fromPageId = :pageId")
  suspend fun getOutgoing(pageId: String): List<WikiLinkEntity>

  @Query("SELECT * FROM wiki_links WHERE toPageId = :pageId")
  suspend fun getIncoming(pageId: String): List<WikiLinkEntity>
}
