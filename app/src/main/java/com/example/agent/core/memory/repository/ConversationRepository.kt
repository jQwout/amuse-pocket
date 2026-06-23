package com.aiopenpocket.agent.core.memory.repository

import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.ConversationTagEntity
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.core.memory.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
class ConversationRepository @Inject constructor(
  private val conversationDao: ConversationDao,
  private val conversationTagDao: ConversationTagDao,
  private val messageDao: MessageDao,
) {
  fun getAllConversations(): Flow<List<Conversation>> =
    combine(conversationDao.getAll(), conversationTagDao.getAll()) { conversations, tags ->
      val tagsByConversation = tags.groupBy({ it.conversationId }, { it.tag })
      conversations.map { it.toDomain(tagsByConversation[it.id].orEmpty()) }
    }

  suspend fun getConversation(id: String): Conversation? =
    conversationDao.getById(id)?.toDomain(conversationTagDao.getTagsForConversation(id))

  suspend fun createConversation(
    title: String,
    personaId: String = "sharp_partner",
    isTutorial: Boolean = false,
    agentId: String? = null,
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
        agentId = agentId,
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

  /**
   * Apply the background annotation produced from the chat log: title, summary and tags. Each part
   * is applied only when non-blank, so a partial LLM result never wipes a good earlier value.
   */
  suspend fun updateAnnotation(id: String, title: String?, summary: String?, tags: List<String>) {
    title?.trim()?.takeIf { it.isNotBlank() }?.let { conversationDao.updateTitle(id, it) }
    summary?.trim()?.takeIf { it.isNotBlank() }?.let { conversationDao.updateSummary(id, it) }
    val normalized = tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct()
    if (normalized.isNotEmpty()) {
      conversationTagDao.deleteForConversation(id)
      normalized.forEach { conversationTagDao.insert(ConversationTagEntity(id, it)) }
    }
  }

  suspend fun getAllTags(): List<String> = conversationTagDao.getAllTags()

  /** Conversation ids that share any tag with [tags], most-overlapping first. */
  suspend fun getConversationIdsByTags(tags: List<String>): List<String> {
    val normalized = tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct()
    if (normalized.isEmpty()) return emptyList()
    return conversationTagDao.getConversationIdsByTags(normalized)
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
      .map { list -> list.find { it.isTutorial }?.toDomain(emptyList()) }
      .first()

  private fun ConversationEntity.toDomain(tags: List<String>) = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    personaId = personaId,
    summary = summary,
    isTutorial = isTutorial,
    tags = tags,
    agentId = agentId,
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

  companion object {
    private const val PLACEHOLDER_TITLE_MAX_CHARS = 40
    private const val DEFAULT_TITLE = "Новый диалог"

    /**
     * Title shown until the background annotator generates a real one: the first message,
     * clipped with an ellipsis. Falls back to a default when the message is blank.
     */
    fun placeholderTitle(firstMessage: String): String {
      val trimmed = firstMessage.trim()
      if (trimmed.isBlank()) return DEFAULT_TITLE
      return if (trimmed.length <= PLACEHOLDER_TITLE_MAX_CHARS) {
        trimmed
      } else {
        trimmed.take(PLACEHOLDER_TITLE_MAX_CHARS).trimEnd() + "…"
      }
    }

    /** Tags are single lowercase tokens; internal whitespace collapses to one hyphen. */
    fun normalizeTag(raw: String): String =
      raw.trim().lowercase().replace(Regex("\\s+"), "-").trim('-')
  }
}
