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
   * All conversations for the chat list and the memory graph. Favorites always come first, then by
   * freshness.
   *
   * The builder's test conversations (`ConversationRepository.BUILDER_TEST_TAG`) are excluded: that is
   * scratch space, not a chat the user started. Normally the builder removes it on close, but if it did
   * not manage to (the process was killed mid-test), "Agent test" stayed in the list forever.
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
   * The freshest chat bound to the mini-agent [agentId]. System conversations (currently the builder's
   * test chat, see `ConversationRepository.BUILDER_TEST_TAG`) are excluded: they are bound to the
   * agentId too, and without the filter "Open chat" on a freshly built agent could land in the scratch
   * test conversation with its own test messages.
   */
  @Query(
    "SELECT * FROM conversations WHERE agentId = :agentId " +
      "AND id NOT IN (SELECT conversationId FROM conversation_tags WHERE tag = :excludedTag) " +
      "ORDER BY updatedAt DESC LIMIT 1",
  )
  suspend fun findLatestByAgent(agentId: String, excludedTag: String): ConversationEntity?

  /** The freshest plain (not an agent's, not a tutorial) chat — a candidate for reuse. */
  @Query("SELECT * FROM conversations WHERE agentId IS NULL AND isTutorial = 0 ORDER BY createdAt DESC LIMIT 1")
  suspend fun findLatestReusableNewChat(): ConversationEntity?

  /** The existing chat of lesson [title] (Roadmap/Library reuse it instead of creating a new one). */
  @Query("SELECT * FROM conversations WHERE title = :title AND isTutorial = 1 ORDER BY updatedAt DESC LIMIT 1")
  suspend fun findLatestTutorialByTitle(title: String): ConversationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(conversation: ConversationEntity)

  @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

  @Query("UPDATE conversations SET summary = :summary, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateSummary(id: String, summary: String, updatedAt: Long = System.currentTimeMillis())

  /** Bumps the conversation's updatedAt — called when a message is added, so the order and "freshness"
   *  reflect real activity, not only the creation/annotation time. */
  @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
  suspend fun touch(id: String, updatedAt: Long = System.currentTimeMillis())

  /** Toggles "favorite". updatedAt is not touched: a favorite is not activity, just pinning. */
  @Query("UPDATE conversations SET isFavorite = :favorite WHERE id = :id")
  suspend fun setFavorite(id: String, favorite: Boolean)

  /** Persists the lesson cursor. updatedAt is not touched: advancing a step is not "conversation activity". */
  @Query("UPDATE conversations SET lessonStepIndex = :stepIndex, lessonFinished = :finished WHERE id = :id")
  suspend fun updateLessonCursor(id: String, stepIndex: Int, finished: Boolean)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun delete(id: String)
}
