package com.aiopenpocket.agent.core.memory.dao

// @trace multi-chat-conversations-b3c93b

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.ConversationTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationTagDao {
  /** Every (conversationId, tag) pair, for grouping tags onto the conversation list reactively. */
  @Query("SELECT * FROM conversation_tags")
  fun getAll(): Flow<List<ConversationTagEntity>>

  @Query("SELECT tag FROM conversation_tags WHERE conversationId = :conversationId ORDER BY tag")
  suspend fun getTagsForConversation(conversationId: String): List<String>

  @Query("SELECT DISTINCT tag FROM conversation_tags ORDER BY tag")
  suspend fun getAllTags(): List<String>

  /** Conversations sharing any of [tags] — the "similar chats" lookup, ranked by overlap count. */
  @Query(
    "SELECT conversationId FROM conversation_tags WHERE tag IN (:tags) " +
      "GROUP BY conversationId ORDER BY COUNT(*) DESC",
  )
  suspend fun getConversationIdsByTags(tags: List<String>): List<String>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(tag: ConversationTagEntity)

  @Query("DELETE FROM conversation_tags WHERE conversationId = :conversationId")
  suspend fun deleteForConversation(conversationId: String)
}
