package com.aiopenpocket.agent.core.memory.repository

import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.core.memory.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
class ConversationRepository @Inject constructor(
  private val conversationDao: ConversationDao,
  private val messageDao: MessageDao,
) {
  fun getAllConversations(): Flow<List<Conversation>> =
    conversationDao.getAll().map { list ->
      list.map { it.toDomain() }
    }

  suspend fun getConversation(id: String): Conversation? =
    conversationDao.getById(id)?.toDomain()

  suspend fun createConversation(
    title: String,
    personaId: String = "sharp_partner",
    isTutorial: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    conversationDao.insert(
      ConversationEntity(
        id = id,
        title = title,
        createdAt = now,
        updatedAt = now,
        personaId = personaId,
        isTutorial = isTutorial,
      ),
    )
    return id
  }

  suspend fun updateTitle(id: String, title: String) {
    conversationDao.updateTitle(id, title)
  }

  suspend fun updateSummary(id: String, summary: String) {
    conversationDao.updateSummary(id, summary)
  }

  suspend fun deleteConversation(id: String) {
    conversationDao.delete(id)
  }

  fun getMessages(conversationId: String): Flow<List<Message>> =
    messageDao.getByConversation(conversationId).map { list ->
      list.map { it.toDomain() }
    }

  suspend fun getRecentMessages(
    conversationId: String,
    limit: Int = LlmContextConfig.FULL_HISTORY_LIMIT,
  ): List<Message> =
    messageDao.getRecent(conversationId, limit).reversed().map { it.toDomain() }

  suspend fun getAllMessages(conversationId: String): List<Message> =
    messageDao.getAllOrdered(conversationId).map { it.toDomain() }

  suspend fun addMessage(
    conversationId: String,
    role: String,
    content: String,
    toolCallsJson: String? = null,
    cardSpec: String? = null,
  ): String {
    val id = UUID.randomUUID().toString()
    messageDao.insert(
      MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        cardSpec = cardSpec,
      ),
    )
    return id
  }

  suspend fun setMessagePinned(messageId: String, pinned: Boolean) {
    messageDao.setPinned(messageId, pinned)
  }

  suspend fun deleteMessages(conversationId: String) {
    messageDao.deleteByConversation(conversationId)
  }

  suspend fun getTutorialConversation(): Conversation? =
    conversationDao.getAll()
      .map { list -> list.find { it.isTutorial }?.toDomain() }
      .first()

  private fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    personaId = personaId,
    summary = summary,
    isTutorial = isTutorial,
  )

  private fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    toolCallsJson = toolCallsJson,
    cardSpec = cardSpec,
    isPinned = isPinned,
    createdAt = createdAt,
  )
}
