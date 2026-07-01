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
   * Окно последних [limit] сообщений (для UI-пагинации): берём свежие в DESC и разворачиваем в ASC
   * на стороне репозитория. Поток переэмитит при изменении таблицы, поэтому новые сообщения попадают
   * в окно автоматически, а «подгрузка старых» — это просто увеличение [limit].
   */
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
  fun getByConversationWindow(conversationId: String, limit: Int): Flow<List<MessageEntity>>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
  suspend fun getRecent(conversationId: String, limit: Int): List<MessageEntity>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
  suspend fun getAllOrdered(conversationId: String): List<MessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(message: MessageEntity)

  @Query("UPDATE messages SET isPinned = :pinned WHERE id = :id")
  suspend fun setPinned(id: String, pinned: Boolean)

  /** Удаление одного сообщения (действие пользователя из чата, не «очистить диалог»). */
  @Query("DELETE FROM messages WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM messages WHERE conversationId = :conversationId")
  suspend fun deleteByConversation(conversationId: String)
}
