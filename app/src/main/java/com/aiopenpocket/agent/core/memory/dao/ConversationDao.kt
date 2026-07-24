package com.aiopenpocket.agent.core.memory.dao

// @trace multi-chat-conversations-b3c93b

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
  // Избранные — всегда в начале, дальше по свежести.
  @Query("SELECT * FROM conversations ORDER BY isFavorite DESC, updatedAt DESC")
  fun getAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ConversationEntity?

  /** Самый свежий чат, привязанный к мини-агенту [agentId]. */
  @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY updatedAt DESC LIMIT 1")
  suspend fun findLatestByAgent(agentId: String): ConversationEntity?

  /** Самый свежий обычный (не агентный, не туториал) чат — кандидат на переиспользование. */
  @Query("SELECT * FROM conversations WHERE agentId IS NULL AND isTutorial = 0 ORDER BY createdAt DESC LIMIT 1")
  suspend fun findLatestReusableNewChat(): ConversationEntity?

  /** Существующий чат урока [title] (Roadmap/Library переиспользуют его вместо нового). */
  @Query("SELECT * FROM conversations WHERE title = :title AND isTutorial = 1 ORDER BY updatedAt DESC LIMIT 1")
  suspend fun findLatestTutorialByTitle(title: String): ConversationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(conversation: ConversationEntity)

  @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

  @Query("UPDATE conversations SET summary = :summary, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateSummary(id: String, summary: String, updatedAt: Long = System.currentTimeMillis())

  /** Поднимает updatedAt диалога — вызывается при добавлении сообщения, чтобы порядок и «свежесть»
   *  отражали реальную активность, а не только время создания/аннотации. */
  @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
  suspend fun touch(id: String, updatedAt: Long = System.currentTimeMillis())

  /** Переключает «избранное». updatedAt не трогаем: избранное — не активность, лишь закрепление. */
  @Query("UPDATE conversations SET isFavorite = :favorite WHERE id = :id")
  suspend fun setFavorite(id: String, favorite: Boolean)

  /** Персистит курсор урока. updatedAt не трогаем: продвижение шага — не «активность диалога». */
  @Query("UPDATE conversations SET lessonStepIndex = :stepIndex, lessonFinished = :finished WHERE id = :id")
  suspend fun updateLessonCursor(id: String, stepIndex: Int, finished: Boolean)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun delete(id: String)
}
