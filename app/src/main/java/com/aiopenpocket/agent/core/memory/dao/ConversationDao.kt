package com.aiopenpocket.agent.core.memory.dao

// @trace multi-chat-conversations-b3c93b

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
  /**
   * Все диалоги для списка чатов и графа памяти. Избранные — всегда в начале, дальше по свежести.
   *
   * Тестовые диалоги конструктора (`ConversationRepository.BUILDER_TEST_TAG`) исключены: это
   * черновое пространство, а не чат, который пользователь заводил. Обычно его убирает сам
   * конструктор при закрытии, но если не успел (процесс убит посреди теста), «Тест агента»
   * оставался в списке навсегда.
   */
  @Query(
    "SELECT * FROM conversations " +
      "WHERE id NOT IN (SELECT conversationId FROM conversation_tags WHERE tag = '" +
      ConversationRepository.BUILDER_TEST_TAG + "') " +
      "ORDER BY isFavorite DESC, updatedAt DESC",
  )
  fun getAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ConversationEntity?

  /**
   * Самый свежий чат, привязанный к мини-агенту [agentId]. Служебные диалоги (сейчас — тестовый чат
   * конструктора, см. `ConversationRepository.BUILDER_TEST_TAG`) исключаются: они тоже привязаны к
   * agentId, и без фильтра «Открыть чат» у только что собранного агента мог попасть в черновой
   * тестовый диалог с его же тестовыми сообщениями.
   */
  @Query(
    "SELECT * FROM conversations WHERE agentId = :agentId " +
      "AND id NOT IN (SELECT conversationId FROM conversation_tags WHERE tag = :excludedTag) " +
      "ORDER BY updatedAt DESC LIMIT 1",
  )
  suspend fun findLatestByAgent(agentId: String, excludedTag: String): ConversationEntity?

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
