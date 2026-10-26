package com.aiopenpocket.agent.core.memory.dao

// @trace multi-chat-conversations-b3c93b

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  fun getByConversation(conversationId: String): Flow<List<MessageEntity>>

  /**
   * A window of the last [limit] messages (for UI pagination): the freshest are taken in DESC and
   * reversed to ASC in the repository. The flow re-emits when the table changes, so new messages get
   * into the window automatically, and "load older" is simply a larger [limit].
   */
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
  fun getByConversationWindow(conversationId: String, limit: Int): Flow<List<MessageEntity>>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
  suspend fun getRecent(conversationId: String, limit: Int): List<MessageEntity>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  suspend fun getAllOrdered(conversationId: String): List<MessageEntity>

  // --- "Prompt" rows: with text or tool calls (empty placeholder cards are not sent to the LLM).
  // The SQL filter repeats the old in-memory `content.isNotBlank() || toolCallsJson != null`, so that
  // the head/tail/count of the prompt window are assembled WITHOUT loading the whole history from disk
  // on every turn. (SQLite TRIM does not strip \n/\t — but such "almost empty" messages are not written
  // in practice.)

  @Query(
    "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId " +
      "AND (TRIM(content) != '' OR toolCallsJson IS NOT NULL)",
  )
  suspend fun countPromptable(conversationId: String): Int

  @Query(
    "SELECT * FROM messages WHERE conversationId = :conversationId " +
      "AND (TRIM(content) != '' OR toolCallsJson IS NOT NULL) ORDER BY createdAt ASC LIMIT :limit",
  )
  suspend fun getFirstPromptable(conversationId: String, limit: Int): List<MessageEntity>

  @Query(
    "SELECT * FROM messages WHERE conversationId = :conversationId " +
      "AND (TRIM(content) != '' OR toolCallsJson IS NOT NULL) ORDER BY createdAt DESC LIMIT :limit",
  )
  suspend fun getLastPromptable(conversationId: String, limit: Int): List<MessageEntity>

  // --- Messages for the background annotator (title/summary/tags): text user/assistant only.

  @Query(
    "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId " +
      "AND role IN ('user', 'assistant') AND TRIM(content) != ''",
  )
  suspend fun countAnnotatable(conversationId: String): Int

  @Query(
    "SELECT * FROM messages WHERE conversationId = :conversationId " +
      "AND role IN ('user', 'assistant') AND TRIM(content) != '' ORDER BY createdAt DESC LIMIT :limit",
  )
  suspend fun getLastAnnotatable(conversationId: String, limit: Int): List<MessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(message: MessageEntity)

  /** Deleting one message (a user action from the chat, not "clear the conversation"). */
  @Query("DELETE FROM messages WHERE id = :id")
  suspend fun deleteById(id: String)
}
